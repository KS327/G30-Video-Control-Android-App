package sg.hexcel.tankeramr.control

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.abs

enum class ConnectionRoute { LOCAL, INTERNET }
enum class Ch05Position { UP_MANUAL, MIDDLE_SAFE, DOWN_AUTONOMOUS, UNKNOWN }
enum class AutonomousState { STOPPED, STARTING, READY, FAULT }

data class OperatorSnapshot(
    val ch05: Ch05Position = Ch05Position.UNKNOWN,
    val ch05Raw: Int? = null,
    val route: ConnectionRoute = ConnectionRoute.LOCAL,
    val internetArmed: Boolean = false,
    val autonomousState: AutonomousState = AutonomousState.STOPPED,
    val autonomousArmed: Boolean = false,
    val neutralInterlock: Boolean = false,
    val queuedTurns: Int = 0,
    val activeTurn: TurnButton? = null,
    val speedMetresPerSecond: Double? = null,
    val rollDegrees: Double? = null,
    val pitchDegrees: Double? = null,
    val telemetrySource: String = "SIMULATOR",
    val message: String = "Waiting for RC channels"
)

/** Pure operator safety state machine. Transport and Android UI are deliberately kept outside. */
class OperatorControl(private val now: () -> Long = System::currentTimeMillis) {
    private val turnQueue = ArrayDeque<TurnButton>()
    private var state = OperatorSnapshot()
    private var lastCh05 = Ch05Position.UNKNOWN
    private var middleSince = 0L
    private var routeInterlockUntil = 0L
    private var autonomousReadyAt = 0L
    private var activeTurnUntil = 0L
    private var turnGapUntil = 0L
    private var lastTelemetryAt = -1L

    fun snapshot(): OperatorSnapshot = state

    fun updateChannels(channels: IntArray): OperatorSnapshot {
        val current = ch05From(channels.getOrNull(4))
        val changed = current != lastCh05
        if (changed) {
            cancelTurns("CH05 changed")
            state = state.copy(internetArmed = false, autonomousArmed = false)
            if (current == Ch05Position.MIDDLE_SAFE) middleSince = now() else middleSince = 0L
            lastCh05 = current
        }
        if (current == Ch05Position.MIDDLE_SAFE && middleSince > 0L && now() - middleSince >= ROUTE_RESET_MS) {
            state = state.copy(route = ConnectionRoute.LOCAL)
        }
        state = state.copy(ch05 = current, ch05Raw = channels.getOrNull(4))
        return tick()
    }

    fun selectRoute(route: ConnectionRoute): OperatorSnapshot {
        if (state.ch05 != Ch05Position.UP_MANUAL) return reject("Route is available only with CH05 up")
        if (state.route == route) return state
        cancelTurns("Route changed")
        state = state.copy(route = route, internetArmed = false, neutralInterlock = true)
        routeInterlockUntil = now() + ROUTE_RESET_MS
        return state.copy(message = "${route.name} selected; neutral interlock 0.5 s").also { state = it }
    }

    fun toggleInternetArm(): OperatorSnapshot {
        if (state.ch05 != Ch05Position.UP_MANUAL || state.route != ConnectionRoute.INTERNET) {
            return reject("INTERNET arm requires CH05 up and INTERNET route")
        }
        if (state.neutralInterlock) return reject("Wait for neutral interlock")
        state = state.copy(internetArmed = !state.internetArmed)
        return state.copy(message = if (state.internetArmed) "INTERNET ARMED" else "INTERNET DISARMED").also { state = it }
    }

    fun toggleAutonomousProcess(): OperatorSnapshot {
        if (state.ch05 == Ch05Position.UP_MANUAL || state.ch05 == Ch05Position.UNKNOWN) {
            return reject("START AUTONOMOUS requires CH05 middle or down")
        }
        state = when (state.autonomousState) {
            AutonomousState.STOPPED, AutonomousState.FAULT -> {
                autonomousReadyAt = now() + AUTONOMOUS_START_MS
                state.copy(autonomousState = AutonomousState.STARTING, autonomousArmed = false,
                    message = "AUTONOMOUS starting (LOCAL)")
            }
            AutonomousState.STARTING, AutonomousState.READY -> {
                cancelTurns("AUTONOMOUS stopped")
                state.copy(autonomousState = AutonomousState.STOPPED, autonomousArmed = false,
                    message = "AUTONOMOUS stopped")
            }
        }
        return state
    }

    fun toggleAutonomousArm(): OperatorSnapshot {
        if (state.ch05 != Ch05Position.DOWN_AUTONOMOUS || state.autonomousState != AutonomousState.READY) {
            return reject("AUTONOMOUS arm requires CH05 down and READY")
        }
        state = state.copy(autonomousArmed = !state.autonomousArmed)
        return state.copy(message = if (state.autonomousArmed) "AUTONOMOUS ARMED" else "AUTONOMOUS DISARMED").also { state = it }
    }

    fun enqueueTurn(turn: TurnButton): OperatorSnapshot {
        if (!manualUiPermitted()) return reject("UI turn disabled by CH05/route/arm state")
        val total = turnQueue.size + if (state.activeTurn != null) 1 else 0
        if (total >= MAX_TOTAL_TURNS) return reject("QUEUE FULL (6/6)")
        turnQueue.addLast(turn)
        state = state.copy(queuedTurns = turnQueue.size, message = "${turn.shortName} queued (${total + 1}/6)")
        return tick()
    }

    fun emergencyStop(reason: String = "Operator stop"): OperatorSnapshot {
        cancelTurns(reason)
        state = state.copy(internetArmed = false, autonomousArmed = false, message = reason)
        return state
    }

    fun updateTelemetry(speedMps: Double, rollDeg: Double, pitchDeg: Double, source: String): OperatorSnapshot {
        lastTelemetryAt = now()
        state = state.copy(
            speedMetresPerSecond = speedMps,
            rollDegrees = rollDeg,
            pitchDegrees = pitchDeg,
            telemetrySource = source.ifBlank { "JETSON" }
        )
        return state
    }

    fun tick(): OperatorSnapshot {
        val t = now()
        if (lastTelemetryAt >= 0L && t - lastTelemetryAt > TELEMETRY_STALE_MS) {
            lastTelemetryAt = -1L
            state = state.copy(speedMetresPerSecond = null, rollDegrees = null, pitchDegrees = null,
                telemetrySource = "STALE")
        }
        if (state.neutralInterlock && t >= routeInterlockUntil) {
            state = state.copy(neutralInterlock = false, message = "Route ready")
        }
        if (state.autonomousState == AutonomousState.STARTING && t >= autonomousReadyAt) {
            state = state.copy(autonomousState = AutonomousState.READY, message = "AUTONOMOUS READY")
        }
        if (state.activeTurn != null && t >= activeTurnUntil) {
            state = state.copy(activeTurn = null)
            turnGapUntil = t + TURN_GAP_MS
        }
        if (state.activeTurn == null && turnQueue.isNotEmpty() && t >= turnGapUntil && manualUiPermitted()) {
            val next = turnQueue.removeFirst()
            state = state.copy(activeTurn = next, queuedTurns = turnQueue.size,
                message = "Executing ${next.shortName}")
            activeTurnUntil = t + TURN_DURATION_MS
        } else {
            state = state.copy(queuedTurns = turnQueue.size)
        }
        return state
    }

    private fun manualUiPermitted(): Boolean = state.ch05 == Ch05Position.UP_MANUAL &&
        !state.neutralInterlock && (state.route == ConnectionRoute.LOCAL || state.internetArmed)

    private fun reject(message: String): OperatorSnapshot = state.copy(message = message).also { state = it }

    private fun cancelTurns(reason: String) {
        turnQueue.clear()
        activeTurnUntil = 0L
        turnGapUntil = 0L
        state = state.copy(activeTurn = null, queuedTurns = 0, message = reason)
    }

    companion object {
        const val MAX_TOTAL_TURNS = 6
        private const val ROUTE_RESET_MS = 500L
        private const val AUTONOMOUS_START_MS = 3000L
        private const val TURN_DURATION_MS = 1950L
        private const val TURN_GAP_MS = 200L
        private const val TELEMETRY_STALE_MS = 1500L

        fun ch05From(value: Int?): Ch05Position = when {
            value == null -> Ch05Position.UNKNOWN
            value >= 1725 -> Ch05Position.UP_MANUAL
            value <= 1275 -> Ch05Position.DOWN_AUTONOMOUS
            else -> Ch05Position.MIDDLE_SAFE
        }
    }
}

/** Versioned JSON wire contract for the later Jetson gateway. */
object ControlProtocol {
    const val VERSION = 1

    fun command(sequence: Long, type: String, payload: JSONObject = JSONObject()): String = JSONObject()
        .put("v", VERSION)
        .put("seq", sequence)
        .put("timestamp_ms", System.currentTimeMillis())
        .put("type", type)
        .put("payload", payload)
        .toString()

    fun rcChannels(sequence: Long, channels: IntArray): String = command(
        sequence,
        "rc_channels",
        JSONObject().put("channels", JSONArray(channels.toList()))
    )

    fun turn(sequence: Long, button: TurnButton): String = command(
        sequence,
        "turn_90",
        JSONObject().put("button", button.wireName)
    )

    fun direction(value: Double?, negative: String, positive: String): String {
        if (value == null) return "—"
        if (abs(value) < 0.05) return "0.0° LEVEL"
        return "%.1f° %s".format(abs(value), if (value < 0) negative else positive)
    }
}
