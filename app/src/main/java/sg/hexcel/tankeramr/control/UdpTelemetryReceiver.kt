package sg.hexcel.tankeramr.control

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class UdpTelemetryReceiver(private val port: Int = 5006) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start(onTelemetry: (Double, Double, Double, String) -> Unit, onError: (String) -> Unit) {
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
                            onTelemetry(
                                payload.getDouble("speed_mps"),
                                payload.getDouble("roll_deg"),
                                payload.getDouble("pitch_deg"),
                                payload.optString("source", "JETSON")
                            )
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
