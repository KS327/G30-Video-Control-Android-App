package sg.hexcel.tankeramr.control

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

data class TelemetryPacket(
    val speedMps: Double?,
    val rollDeg: Double?,
    val pitchDeg: Double?,
    val yawDeg: Double?,
    val alignmentErrorM: Double?,
    val headingErrorDeg: Double?,
    val source: String,
    val ch05: Ch05Position,
    val ch05Raw: Int?,
    val route: ConnectionRoute,
    val internetArmed: Boolean,
    val sidexAvailable: Boolean,
    val sidexWaitReason: String = "",
    val sidexState: SidexState,
    val sidexStage: String,
    val sidexFaultCode: String,
    val sidexLap: Int,
    val obstaclePaused: Boolean,
    val sensorState: String,
    val sidexArmed: Boolean,
    val queuedTurns: Int,
    val totalTurns: Int,
    val activeTurn: TurnButton?,
    val message: String,
    val sidexCourseProfile: String = "RAMP_LIVE",
    val sidexSimulatedGeometry: Boolean = false,
    val sidexStageDistanceM: Double? = null,
    val sidexPitchDeltaDeg: Double? = null,
    val sidexRectangleSideAM: Double? = null,
    val sidexRectangleSideBM: Double? = null,
    val teachAvailable: Boolean = false,
    val teachRecording: Boolean = false,
    val teachSession: String = "",
    val teachDurationS: Double = 0.0,
    val teachMarkerIndex: Int = 0,
    val teachMarkerCount: Int = 0,
    val teachNextMarker: String = "",
    val teachFaultCode: String = "",
    val teachMessage: String = "",
    val mapAvailable: Boolean = false,
    val mapState: String = "UNAVAILABLE",
    val mapRecording: Boolean = false,
    val mapDurationS: Double = 0.0,
    val mapFaultCode: String = "",
    val mapMessage: String = "",
    val safetyState: String = "UNAVAILABLE",
    val safetyReason: String = "",
    val collisionMonitorActive: Boolean = false,
    val collisionOverrideMode: String = "AUTO"
)

class UdpTelemetryReceiver(private val port: Int = 5006) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start(onTelemetry: (TelemetryPacket) -> Unit, onError: (String) -> Unit) {
        if (running) return
        running = true
        thread(name = "udp-telemetry-receiver", isDaemon = true) {
            try {
                DatagramSocket(port).use { udp ->
                    socket = udp
                    udp.soTimeout = 1000
                    val buffer = ByteArray(4096)
                    while (running) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            udp.receive(packet)
                            val root = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                            if (root.optInt("v") != ControlProtocol.VERSION || root.optString("type") != "telemetry") continue
                            val payload = root.getJSONObject("payload")
                            fun boundedDouble(key: String, minimum: Double, maximum: Double): Double? =
                                if (payload.isNull(key)) null else payload.optDouble(key)
                                    .takeIf { it.isFinite() && it in minimum..maximum }
                            val telemetryPacket = TelemetryPacket(
                                speedMps = boundedDouble("speed_mps", 0.0, 1.0),
                                rollDeg = boundedDouble("roll_deg", -180.0, 180.0),
                                pitchDeg = boundedDouble("pitch_deg", -90.0, 90.0),
                                yawDeg = boundedDouble("yaw_deg", -180.0, 180.0),
                                alignmentErrorM = boundedDouble("sidex_alignment_error_m", 0.0, 10.0),
                                headingErrorDeg = boundedDouble("sidex_heading_error_deg", -180.0, 180.0),
                                source = payload.optString("source", "JETSON"),
                                ch05 = OperatorControl.ch05FromWire(payload.optString("ch05")),
                                ch05Raw = if (payload.isNull("ch05_raw")) null else payload.optInt("ch05_raw"),
                                route = runCatching { ConnectionRoute.valueOf(payload.optString("route", "LOCAL")) }
                                    .getOrDefault(ConnectionRoute.LOCAL),
                                internetArmed = payload.optBoolean("internet_armed", false),
                                sidexAvailable = payload.optBoolean("sidex_available", false),
                                sidexWaitReason = payload.optString("sidex_wait_reason", ""),
                                sidexState = runCatching {
                                    SidexState.valueOf(payload.optString("sidex_state", "STOPPED"))
                                }.getOrDefault(SidexState.FAULT),
                                sidexStage = payload.optString("sidex_stage", "IDLE"),
                                sidexFaultCode = payload.optString("sidex_fault_code", ""),
                                sidexLap = payload.optInt("sidex_lap", 0),
                                obstaclePaused = payload.optBoolean("sidex_obstacle_paused", false),
                                sensorState = payload.optString("sensor_state", "UNKNOWN"),
                                sidexArmed = payload.optBoolean("sidex_armed", false),
                                queuedTurns = payload.optInt("queued_turns", 0),
                                totalTurns = payload.optInt("total_turns", payload.optInt("queued_turns", 0)),
                                activeTurn = TurnButton.values().firstOrNull {
                                    it.wireName == payload.optString("active_turn")
                                },
                                message = payload.optString("control_status", "Jetson connected"),
                                sidexCourseProfile = payload.optString("sidex_course_profile", "RAMP_LIVE"),
                                sidexSimulatedGeometry = payload.optBoolean("sidex_simulated_geometry", false),
                                sidexStageDistanceM = boundedDouble("sidex_stage_distance_m", 0.0, 100.0),
                                sidexPitchDeltaDeg = boundedDouble("sidex_pitch_delta_deg", 0.0, 90.0),
                                sidexRectangleSideAM = boundedDouble("sidex_rectangle_side_a_m", 0.0, 100.0),
                                sidexRectangleSideBM = boundedDouble("sidex_rectangle_side_b_m", 0.0, 100.0),
                                teachAvailable = payload.optBoolean("sidex_teach_available", false),
                                teachRecording = payload.optBoolean("sidex_teach_recording", false),
                                teachSession = payload.optString("sidex_teach_session", ""),
                                teachDurationS = boundedDouble("sidex_teach_duration_s", 0.0, 86400.0) ?: 0.0,
                                teachMarkerIndex = payload.optInt("sidex_teach_marker_index", 0),
                                teachMarkerCount = payload.optInt("sidex_teach_marker_count", 0),
                                teachNextMarker = payload.optString("sidex_teach_next_marker", ""),
                                teachFaultCode = payload.optString("sidex_teach_fault_code", ""),
                                teachMessage = payload.optString("sidex_teach_message", ""),
                                mapAvailable = payload.optBoolean("sidex_map_available", false),
                                mapState = payload.optString("sidex_map_state", "UNAVAILABLE"),
                                mapRecording = payload.optBoolean("sidex_map_recording", false),
                                mapDurationS = boundedDouble("sidex_map_duration_s", 0.0, 86400.0) ?: 0.0,
                                mapFaultCode = payload.optString("sidex_map_fault_code", ""),
                                mapMessage = payload.optString("sidex_map_message", ""),
                                safetyState = payload.optString("sidex_safety_state", "UNAVAILABLE"),
                                safetyReason = payload.optString("sidex_safety_reason", ""),
                                collisionMonitorActive = payload.optBoolean("sidex_collision_monitor_active", false),
                                collisionOverrideMode = payload.optString("sidex_collision_override_mode", "AUTO")
                            )
                            onTelemetry(telemetryPacket)
                        } catch (_: SocketTimeoutException) {
                            // Periodically wake so close() is prompt.
                        } catch (e: Exception) {
                            if (running) onError("Telemetry packet rejected: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) onError("Telemetry receiver failed: ${e.message}")
            } finally {
                socket = null
                running = false
            }
        }
    }

    fun close() {
        running = false
        socket?.close()
        socket = null
    }
}
