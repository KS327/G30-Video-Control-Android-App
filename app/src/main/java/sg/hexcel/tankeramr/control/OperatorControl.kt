package sg.hexcel.tankeramr.control

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.abs

enum class ConnectionRoute { LOCAL, INTERNET }
enum class Ch05Position { UP_RF_SIDEX, MIDDLE_SAFE, DOWN_INTERNET, UNKNOWN }
enum class SidexState { STOPPED, STARTING, RUNNING, PAUSED, COMPLETE, FAULT }

data class OperatorSnapshot(
    val ch05: Ch05Position = Ch05Position.UNKNOWN,
    val ch05Raw: Int? = null,
    val route: ConnectionRoute = ConnectionRoute.LOCAL,
    val internetArmed: Boolean = false,
    val sidexAvailable: Boolean = false,
    val sidexWaitReason: String = "",
    val sidexState: SidexState = SidexState.STOPPED,
    val sidexStage: String = "IDLE",
    val sidexFaultCode: String = "",
    val sidexArmed: Boolean = false,
    val sidexLap: Int = 0,
    val obstaclePaused: Boolean = false,
    val neutralInterlock: Boolean = false,
    val queuedTurns: Int = 0,
    val totalTurns: Int = 0,
    val activeTurn: TurnButton? = null,
    val speedMetresPerSecond: Double? = null,
    val rollDegrees: Double? = null,
    val pitchDegrees: Double? = null,
    val yawDegrees: Double? = null,
    val alignmentErrorMetres: Double? = null,
    val headingErrorDegrees: Double? = null,
    val sidexCourseProfile: String = "RAMP_LIVE",
    val sidexSimulatedGeometry: Boolean = false,
    val sidexStageDistanceMetres: Double? = null,
    val sidexPitchDeltaDegrees: Double? = null,
    val sidexRectangleSideAMetres: Double? = null,
    val sidexRectangleSideBMetres: Double? = null,
    val teachAvailable: Boolean = false,
    val teachRecording: Boolean = false,
    val teachSession: String = "",
    val teachDurationSeconds: Double = 0.0,
    val teachMarkerIndex: Int = 0,
    val teachMarkerCount: Int = 0,
    val teachNextMarker: String = "",
    val teachFaultCode: String = "",
    val teachMessage: String = "",
    val mapAvailable: Boolean = false,
    val mapState: String = "UNAVAILABLE",
    val mapRecording: Boolean = false,
    val mapDurationSeconds: Double = 0.0,
    val mapFaultCode: String = "",
    val mapMessage: String = "",
    val safetyState: String = "UNAVAILABLE",
    val safetyReason: String = "",
    val collisionMonitorActive: Boolean = false,
    val collisionOverrideMode: String = "AUTO",
    val telemetrySource: String = "SIMULATOR",
    val sensorState: String = "UNKNOWN",
    val message: String = "Waiting for RC channels"
)

/** Pure operator state machine. Transport and Android UI are deliberately kept outside. */
class OperatorControl(
    sidexAvailableByDefault: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val turnQueue = ArrayDeque<TurnButton>()
    private var state = OperatorSnapshot(
        sidexAvailable = sidexAvailableByDefault,
        sensorState = if (sidexAvailableByDefault) "READY" else "UNKNOWN"
    )
    private var lastCh05 = Ch05Position.UNKNOWN
    private var routeInterlockUntil = 0L
    private var sidexRunningAt = 0L
    private var activeTurnUntil = 0L
    private var turnGapUntil = 0L
    private var lastTelemetryAt = -1L

    fun snapshot(): OperatorSnapshot = state

    fun updateChannels(channels: IntArray): OperatorSnapshot {
        val current = ch05From(channels.getOrNull(4))
        applyCh05(current, channels.getOrNull(4))
        return tick()
    }

    private fun applyCh05(current: Ch05Position, raw: Int?) {
        if (current != lastCh05) {
            cancelTurns("CH05 changed")
            val leavingSidex = current != Ch05Position.UP_RF_SIDEX && isSidexActive(state.sidexState)
            state = state.copy(
                internetArmed = false,
                sidexState = if (leavingSidex) SidexState.STOPPED else state.sidexState,
                sidexStage = if (leavingSidex) "IDLE" else state.sidexStage,
                sidexArmed = if (leavingSidex) false else state.sidexArmed,
                message = if (leavingSidex) "SIDEX stopped: CH05 left UP" else state.message
            )
            lastCh05 = current
        }
        state = state.copy(
            ch05 = current,
            ch05Raw = raw,
            route = routeForCh05(current, state.route)
        )
    }

    fun toggleInternetArm(): OperatorSnapshot {
        if (state.ch05 != Ch05Position.DOWN_INTERNET) {
            return reject("INTERNET arm requires CH05 down")
        }
        if (state.neutralInterlock) return reject("Wait for neutral interlock")
        state = state.copy(internetArmed = !state.internetArmed)
        return state.copy(message = if (state.internetArmed) "INTERNET ARMED" else "INTERNET DISARMED")
            .also { state = it }
    }

    fun toggleSidexDemo(): OperatorSnapshot {
        if (state.ch05 != Ch05Position.UP_RF_SIDEX) return reject("SIDEX DEMO requires CH05 up")
        if (!state.sidexAvailable) return reject("SIDEX backend is not ready")
        if (state.telemetrySource == "STALE") return reject("SIDEX DEMO requires fresh telemetry")

        state = when (state.sidexState) {
            SidexState.STOPPED, SidexState.COMPLETE, SidexState.FAULT -> {
                cancelTurns("SIDEX starting")
                sidexRunningAt = now() + SIDEX_START_MS
                state.copy(
                    sidexState = SidexState.STARTING,
                    sidexStage = "PREFLIGHT",
                    sidexFaultCode = "",
                    sidexArmed = false,
                    obstaclePaused = false,
                    message = "SIDEX DEMO starting"
                )
            }
            SidexState.STARTING, SidexState.RUNNING, SidexState.PAUSED -> {
                sidexRunningAt = 0L
                state.copy(
                    sidexState = SidexState.STOPPED,
                    sidexStage = "IDLE",
                    sidexArmed = false,
                    obstaclePaused = false,
                    message = "SIDEX DEMO stopped"
                )
            }
        }
        return state
    }

    fun enqueueTurn(turn: TurnButton): OperatorSnapshot {
        if (!manualUiPermitted()) return reject("UI turn disabled by CH05/route/SIDEX state")
        val total = turnQueue.size + if (state.activeTurn != null) 1 else 0
        if (total >= MAX_TOTAL_TURNS) return reject("TURN LIMIT (4/4)")
        turnQueue.addLast(turn)
        state = state.copy(
            queuedTurns = turnQueue.size,
            totalTurns = total + 1,
            message = "${turn.shortName} requested (${total + 1}/4)"
        )
        return tick()
    }

    fun emergencyStop(reason: String = "Operator stop"): OperatorSnapshot {
        cancelTurns(reason)
        sidexRunningAt = 0L
        state = state.copy(
            internetArmed = false,
            sidexState = SidexState.STOPPED,
            sidexStage = "IDLE",
            sidexArmed = false,
            obstaclePaused = false,
            message = reason
        )
        return state
    }

    fun updateRemote(packet: TelemetryPacket): OperatorSnapshot {
        lastTelemetryAt = now()
        applyCh05(packet.ch05, packet.ch05Raw)
        val sidexAllowed = packet.ch05 == Ch05Position.UP_RF_SIDEX
        val preserveTerminal = packet.sidexState == SidexState.COMPLETE || packet.sidexState == SidexState.FAULT
        state = state.copy(
            speedMetresPerSecond = packet.speedMps,
            rollDegrees = packet.rollDeg,
            pitchDegrees = packet.pitchDeg,
            yawDegrees = packet.yawDeg,
            alignmentErrorMetres = packet.alignmentErrorM,
            headingErrorDegrees = packet.headingErrorDeg,
            sidexCourseProfile = packet.sidexCourseProfile,
            sidexSimulatedGeometry = packet.sidexSimulatedGeometry,
            sidexStageDistanceMetres = packet.sidexStageDistanceM,
            sidexPitchDeltaDegrees = packet.sidexPitchDeltaDeg,
            sidexRectangleSideAMetres = packet.sidexRectangleSideAM,
            sidexRectangleSideBMetres = packet.sidexRectangleSideBM,
            teachAvailable = packet.teachAvailable,
            teachRecording = packet.teachRecording,
            teachSession = packet.teachSession,
            teachDurationSeconds = packet.teachDurationS,
            teachMarkerIndex = packet.teachMarkerIndex,
            teachMarkerCount = packet.teachMarkerCount,
            teachNextMarker = packet.teachNextMarker,
            teachFaultCode = packet.teachFaultCode,
            teachMessage = packet.teachMessage,
            mapAvailable = packet.mapAvailable,
            mapState = packet.mapState,
            mapRecording = packet.mapRecording,
            mapDurationSeconds = packet.mapDurationS,
            mapFaultCode = packet.mapFaultCode,
            mapMessage = packet.mapMessage,
            safetyState = packet.safetyState,
            safetyReason = packet.safetyReason,
            collisionMonitorActive = packet.collisionMonitorActive,
            collisionOverrideMode = packet.collisionOverrideMode,
            telemetrySource = packet.source.ifBlank { "JETSON" },
            route = routeForCh05(packet.ch05, state.route),
            internetArmed = packet.internetArmed,
            sidexAvailable = packet.sidexAvailable,
            sidexWaitReason = packet.sidexWaitReason,
            sidexState = if (sidexAllowed || preserveTerminal) packet.sidexState else SidexState.STOPPED,
            sidexStage = if (sidexAllowed || preserveTerminal) packet.sidexStage else "IDLE",
            sidexFaultCode = packet.sidexFaultCode,
            sidexArmed = sidexAllowed && packet.sidexArmed,
            sidexLap = packet.sidexLap,
            obstaclePaused = sidexAllowed && packet.obstaclePaused,
            sensorState = packet.sensorState,
            queuedTurns = packet.queuedTurns,
            totalTurns = packet.totalTurns,
            activeTurn = packet.activeTurn,
            message = packet.message
        )
        return state
    }

    fun tick(): OperatorSnapshot {
        val t = now()
        if (lastTelemetryAt >= 0L && t - lastTelemetryAt > TELEMETRY_STALE_MS) {
            lastTelemetryAt = -1L
            val wasActive = isSidexActive(state.sidexState)
            state = state.copy(
                speedMetresPerSecond = null,
                rollDegrees = null,
                pitchDegrees = null,
                yawDegrees = null,
                alignmentErrorMetres = null,
                headingErrorDegrees = null,
                telemetrySource = "STALE",
                ch05 = Ch05Position.UNKNOWN,
                ch05Raw = null,
                internetArmed = false,
                sidexAvailable = false,
                sidexWaitReason = "TELEMETRY LOST",
                sidexState = if (wasActive) SidexState.FAULT else SidexState.STOPPED,
                sidexStage = if (wasActive) "TELEMETRY_LOST" else "IDLE",
                sidexFaultCode = if (wasActive) "TELEMETRY_LOST" else "",
                sidexArmed = false,
                obstaclePaused = false,
                sensorState = "STALE",
                message = "Jetson telemetry lost; controls are locked"
            )
            lastCh05 = Ch05Position.UNKNOWN
        }
        if (state.neutralInterlock && t >= routeInterlockUntil) {
            state = state.copy(neutralInterlock = false, message = "Route ready")
        }
        if (state.sidexState == SidexState.STARTING && sidexRunningAt > 0L && t >= sidexRunningAt) {
            sidexRunningAt = 0L
            state = state.copy(
                sidexState = SidexState.RUNNING,
                sidexStage = "ALIGN_STAIRS",
                sidexArmed = true,
                message = "SIDEX DEMO running"
            )
        }
        if (state.activeTurn != null && t >= activeTurnUntil) {
            state = state.copy(activeTurn = null)
            turnGapUntil = t + TURN_GAP_MS
        }
        if (state.activeTurn == null && turnQueue.isNotEmpty() && t >= turnGapUntil && manualUiPermitted()) {
            val next = turnQueue.removeFirst()
            state = state.copy(
                activeTurn = next,
                queuedTurns = turnQueue.size,
                totalTurns = turnQueue.size + 1,
                message = "Executing ${next.shortName}"
            )
            activeTurnUntil = t + TURN_DURATION_MS
        } else {
            state = state.copy(
                queuedTurns = turnQueue.size,
                totalTurns = turnQueue.size + if (state.activeTurn != null) 1 else 0
            )
        }
        return state
    }

    private fun manualUiPermitted(): Boolean = (
        state.ch05 == Ch05Position.UP_RF_SIDEX ||
            (state.ch05 == Ch05Position.DOWN_INTERNET && state.internetArmed)
        ) && !state.neutralInterlock && !isSidexActive(state.sidexState)

    private fun reject(message: String): OperatorSnapshot = state.copy(message = message).also { state = it }

    private fun cancelTurns(reason: String) {
        turnQueue.clear()
        activeTurnUntil = 0L
        turnGapUntil = 0L
        state = state.copy(activeTurn = null, queuedTurns = 0, totalTurns = 0, message = reason)
    }

    companion object {
        const val MAX_TOTAL_TURNS = 4
        private const val ROUTE_RESET_MS = 500L
        private const val SIDEX_START_MS = 3000L
        private const val TURN_DURATION_MS = 1950L
        private const val TURN_GAP_MS = 200L
        private const val TELEMETRY_STALE_MS = 1500L

        fun ch05From(value: Int?): Ch05Position = when {
            value == null -> Ch05Position.UNKNOWN
            value >= 1725 -> Ch05Position.UP_RF_SIDEX
            value <= 1275 -> Ch05Position.DOWN_INTERNET
            else -> Ch05Position.MIDDLE_SAFE
        }

        fun ch05FromWire(value: String): Ch05Position = when (value.uppercase()) {
            "UP_RF_SIDEX", "UP_MANUAL" -> Ch05Position.UP_RF_SIDEX
            "MIDDLE_SAFE" -> Ch05Position.MIDDLE_SAFE
            "DOWN_INTERNET", "DOWN_AUTONOMOUS" -> Ch05Position.DOWN_INTERNET
            else -> Ch05Position.UNKNOWN
        }

        fun ch05DisplayValue(position: Ch05Position): Int? = when (position) {
            Ch05Position.DOWN_INTERNET -> 1050
            Ch05Position.MIDDLE_SAFE -> 1500
            Ch05Position.UP_RF_SIDEX -> 1950
            Ch05Position.UNKNOWN -> null
        }

        private fun routeForCh05(current: Ch05Position, fallback: ConnectionRoute): ConnectionRoute = when (current) {
            Ch05Position.UP_RF_SIDEX -> ConnectionRoute.LOCAL
            Ch05Position.DOWN_INTERNET -> ConnectionRoute.INTERNET
            Ch05Position.MIDDLE_SAFE, Ch05Position.UNKNOWN -> fallback
        }

        fun isSidexActive(value: SidexState): Boolean = value in setOf(
            SidexState.STARTING, SidexState.RUNNING, SidexState.PAUSED
        )
    }
}

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

    fun sidex(sequence: Long, action: String, operatorConfirmed: Boolean = false): String = command(
        sequence,
        "sidex_demo",
        JSONObject()
            .put("action", action)
            .put("profile", "draft")
            .put("operator_confirmed", operatorConfirmed)
    )

    fun sidexTeach(sequence: Long, action: String): String = command(
        sequence,
        "sidex_teach",
        JSONObject().put("action", action)
    )

    fun sidexMap(sequence: Long, action: String): String = command(
        sequence,
        "sidex_map",
        JSONObject().put("action", action)
    )

    fun direction(value: Double?, negative: String, positive: String): String {
        if (value == null) return "—"
        if (abs(value) < 0.5) return "0.0°"
        return "%.1f° %s".format(abs(value), if (value < 0) negative else positive)
    }
}
