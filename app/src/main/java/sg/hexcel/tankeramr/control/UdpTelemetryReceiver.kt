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
    val source: String,
    val ch05: Ch05Position,
    val ch05Raw: Int?,
    val route: ConnectionRoute,
    val internetArmed: Boolean,
    val autonomousState: AutonomousState,
    val autonomousArmed: Boolean,
    val queuedTurns: Int,
    val activeTurn: TurnButton?,
    val message: String
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
                            fun nullableDouble(key: String): Double? =
                                if (payload.isNull(key)) null else payload.optDouble(key).takeIf { !it.isNaN() }
                            val telemetryPacket = TelemetryPacket(
                                speedMps = nullableDouble("speed_mps"),
                                rollDeg = nullableDouble("roll_deg"),
                                pitchDeg = nullableDouble("pitch_deg"),
                                source = payload.optString("source", "JETSON"),
                                ch05 = runCatching { Ch05Position.valueOf(payload.optString("ch05")) }
                                    .getOrDefault(Ch05Position.UNKNOWN),
                                ch05Raw = if (payload.isNull("ch05_raw")) null else payload.optInt("ch05_raw"),
                                route = runCatching { ConnectionRoute.valueOf(payload.optString("route", "LOCAL")) }
                                    .getOrDefault(ConnectionRoute.LOCAL),
                                internetArmed = payload.optBoolean("internet_armed", false),
                                autonomousState = runCatching {
                                    AutonomousState.valueOf(payload.optString("autonomous_state", "STOPPED"))
                                }.getOrDefault(AutonomousState.FAULT),
                                autonomousArmed = payload.optBoolean("autonomous_armed", false),
                                queuedTurns = payload.optInt("queued_turns", 0),
                                activeTurn = TurnButton.values().firstOrNull {
                                    it.wireName == payload.optString("active_turn")
                                },
                                message = payload.optString("control_status", "Jetson connected")
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
