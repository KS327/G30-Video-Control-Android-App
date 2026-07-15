package sg.hexcel.tankeramr.control

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class UdpCommandSender(
    private var host: String = "192.168.144.20",
    private var port: Int = 5005
) : CommandSender {
    override val name: String = "UDP"
    private var onStatusCallback: ((String) -> Unit)? = null

    fun setTarget(newHost: String, newPort: Int) {
        host = newHost.trim()
        port = newPort
    }

    fun hostText(): String = host
    fun portText(): Int = port
    fun targetText(): String = "$host:$port"

    override fun start(context: Context, onStatus: (String) -> Unit) {
        onStatusCallback = onStatus
        onStatus("UDP sender ready: $host:$port")
    }

    override fun send(text: String) {
        val targetHost = host
        val targetPort = port
        thread(name = "udp-command-send", isDaemon = true) {
            try {
                DatagramSocket().use { socket ->
                    val data = text.toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(data, data.size, InetAddress.getByName(targetHost), targetPort)
                    socket.send(packet)
                }
            } catch (e: Exception) {
                onStatusCallback?.invoke("UDP send failed to $targetHost:$targetPort - ${e.message}")
            }
        }
    }

    override fun close() {
        onStatusCallback = null
    }
}
