package sg.hexcel.tankeramr.video

import android.widget.TextView
import com.skydroid.fpvplayer.FPVWidget
import com.skydroid.fpvplayer.PlayerType
import com.skydroid.fpvplayer.RtspTransport
import com.skydroid.rcsdk.utils.RCSDKUtils

class VideoGridController(
    private val widgets: List<FPVWidget>,
    private val labels: List<TextView>
) {
    private val urls = MutableList(widgets.size) { "" }

    fun play(slot: Int, url: String) {
        if (slot !in widgets.indices || url.isBlank()) return
        val cleanedUrl = url.trim()
        val w = widgets[slot]
        urls[slot] = cleanedUrl
        labels[slot].text = "CAM ${slot + 1}: ${displayName(cleanedUrl)}"

        w.stop()
        // Some Android devices cannot hardware-decode four simultaneous streams.
        // Keep hardware decode on first stream only; use software decode for the remaining streams.
        w.usingMediaCodec = slot == 0
        w.url = cleanedUrl
        w.playerType = PlayerType.ONLY_SKY
        w.rtspTranstype = RtspTransport.AUTO
        w.rcType = RCSDKUtils.getDeviceType().value
        w.start()
    }

    fun playAllReplacing(newUrls: List<String>) {
        stopAll(clearLabels = true)
        newUrls.take(widgets.size).forEachIndexed { index, url ->
            if (url.isNotBlank()) play(index, url)
        }
    }

    fun stop(slot: Int, clearLabel: Boolean = true) {
        if (slot !in widgets.indices) return
        widgets[slot].stop()
        urls[slot] = ""
        if (clearLabel) labels[slot].text = "CAM ${slot + 1}: not set"
    }

    fun stopAll(clearLabels: Boolean = true) {
        widgets.indices.forEach { stop(it, clearLabel = clearLabels) }
    }

    fun currentUrl(slot: Int): String = urls.getOrNull(slot).orEmpty()

    private fun displayName(url: String): String {
        val host = Regex("rtsp://(?:[^@/]+@)?([^:/]+)").find(url)?.groupValues?.getOrNull(1)
        return host ?: url
    }
}
