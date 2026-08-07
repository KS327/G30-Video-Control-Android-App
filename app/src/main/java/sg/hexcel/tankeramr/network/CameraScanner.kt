package sg.hexcel.tankeramr.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

class CameraScanner {
    data class HostCandidate(
        val ip: String,
        val openPorts: List<Int>,
        val primaryRtspUrl: String,
        val candidateUrls: List<String>
    ) {
        override fun toString(): String = "$ip  ports=$openPorts"
    }

    suspend fun scanSubnet(prefix: String = "192.168.144", start: Int = 2, end: Int = 254): List<HostCandidate> = coroutineScope {
        (start..end).map { last ->
            async(Dispatchers.IO) { scanIp("$prefix.$last") }
        }.awaitAll().filterNotNull().sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
    }

    companion object {
        /**
         * Confirmed JINGYANG / XM 1080P camera RTSP pattern for username admin and empty password.
         * All four installed cameras were verified as HEVC 1920x1080 @ 25 fps on stream=0.
         * Their stream=1 profiles are inconsistent (352x288 or 704x576) and caused distorted
         * rendering on the G30, so the verified main stream is the production default.
         */
        private const val DEFAULT_STREAM = 0

        const val C12_IP = "192.168.144.108"
        const val C12_VISIBLE_URL = "rtsp://$C12_IP:554/stream=1"
        const val C12_THERMAL_URL = "rtsp://$C12_IP:555/stream=2"
        val VERIFIED_IP_CAMERA_IPS = listOf(
            "192.168.144.100",
            "192.168.144.110",
            "192.168.144.130",
            "192.168.144.120"
        )

        fun buildXmEyeRtspUrl(ip: String, stream: Int = DEFAULT_STREAM): String {
            return "rtsp://admin:@$ip:554/user=admin&password=&channel=1&stream=$stream.sdp"
        }

        fun preferMainStream(url: String): String {
            val trimmed = url.trim()
            if (!trimmed.startsWith("rtsp://admin:@") ||
                !trimmed.contains(":554/user=admin&password=&channel=1&stream=1.sdp")) return trimmed
            return trimmed.replace("&stream=1.sdp", "&stream=0.sdp")
        }

        fun defaultSixCameraUrls(): List<String> = listOf(
            C12_VISIBLE_URL,
            *VERIFIED_IP_CAMERA_IPS.map(::buildXmEyeRtspUrl).toTypedArray(),
            ""
        )
    }

    private fun scanIp(ip: String): HostCandidate? {
        val ports = listOf(554, 555, 80, 8899, 34567, 8554, 8080)
        val open = ports.filter { tcpOpen(ip, it, timeoutMs = 220) }
        if (open.isEmpty()) return null

        val urls = buildList {
            if (554 in open) {
                add(buildXmEyeRtspUrl(ip, stream = 0))
                add("rtsp://$ip:554/user=admin&password=&channel=1&stream=0.sdp")
                add("rtsp://$ip:554/stream=0")

                // Optional fallback only. Put stream=1 below stream=0.
                add(buildXmEyeRtspUrl(ip, stream = 1))
                add("rtsp://$ip:554/user=admin&password=&channel=1&stream=1.sdp")
                add("rtsp://$ip:554/stream=1")
            }
            if (555 in open) add("rtsp://$ip:555/stream=2")
            if (8554 in open) add("rtsp://$ip:8554/live")
        }.distinct()

        if (urls.isEmpty()) return null
        return HostCandidate(
            ip = ip,
            openPorts = open,
            primaryRtspUrl = urls.first(),
            candidateUrls = urls
        )
    }

    private fun tcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
