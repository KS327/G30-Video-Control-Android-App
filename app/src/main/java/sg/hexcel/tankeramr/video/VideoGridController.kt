package sg.hexcel.tankeramr.video

import android.graphics.Color
import android.widget.TextView
import com.skydroid.fpvplayer.FPVWidget
import com.skydroid.fpvplayer.OnPlayerStateListener
import com.skydroid.fpvplayer.PlayerType
import com.skydroid.fpvplayer.RtspTransport
import com.skydroid.fpvplayer.ffmpeg.FrameInfo
import com.skydroid.rcsdk.utils.RCSDKUtils

class VideoGridController(
    private val widgets: List<FPVWidget>,
    private val labels: List<TextView>,
    private val names: List<String>,
    private val onStateChanged: (slot: Int, state: StreamState) -> Unit = { _, _ -> }
) {
    enum class StreamState { NOT_SET, PAUSED, CONNECTING, LIVE, OFFLINE }

    private val urls = MutableList(widgets.size) { "" }
    private val playing = MutableList(widgets.size) { false }
    private val states = MutableList(widgets.size) { StreamState.NOT_SET }
    private var primarySlot = 0

    init {
        require(widgets.size == labels.size && widgets.size == names.size)
        widgets.forEachIndexed { slot, widget ->
            widget.onPlayerStateListener = object : OnPlayerStateListener {
                override fun onConnected() {
                    updateState(slot, StreamState.LIVE)
                }

                override fun onDisconnect() {
                    if (playing[slot]) updateState(slot, StreamState.OFFLINE)
                }

                override fun onReadFrame(frameInfo: FrameInfo?) = Unit
            }
            updateState(slot, StreamState.NOT_SET)
        }
    }

    fun setPrimarySlot(slot: Int) {
        if (slot in widgets.indices) primarySlot = slot
    }

    fun setUrl(slot: Int, url: String, playNow: Boolean) {
        if (slot !in widgets.indices) return
        val cleaned = url.trim()
        val changed = urls[slot] != cleaned
        urls[slot] = cleaned

        if (cleaned.isBlank()) {
            widgets[slot].stop()
            playing[slot] = false
            updateState(slot, StreamState.NOT_SET)
            return
        }

        if (playNow) {
            if (changed || !playing[slot]) play(slot)
        } else {
            widgets[slot].stop()
            playing[slot] = false
            updateState(slot, StreamState.PAUSED)
        }
    }

    fun setUrls(newUrls: List<String>) {
        widgets.indices.forEach { slot ->
            setUrl(slot, newUrls.getOrNull(slot).orEmpty(), playNow = false)
        }
    }

    fun play(slot: Int) {
        if (slot !in widgets.indices || urls[slot].isBlank()) return
        val widget = widgets[slot]
        widget.stop()
        widget.usingMediaCodec = slot == primarySlot
        widget.url = urls[slot]
        widget.playerType = PlayerType.AUTO
        widget.rtspTranstype = RtspTransport.AUTO
        widget.rcType = RCSDKUtils.getDeviceType().value
        widget.isThreadMaxPriority = slot == primarySlot
        playing[slot] = true
        updateState(slot, StreamState.CONNECTING)
        widget.start()
    }

    fun playVisible(visibleSlots: Set<Int>) {
        widgets.indices.forEach { slot ->
            if (slot in visibleSlots && urls[slot].isNotBlank()) {
                if (!playing[slot]) play(slot)
            } else if (playing[slot]) {
                pause(slot)
            }
        }
    }

    fun pause(slot: Int) {
        if (slot !in widgets.indices) return
        widgets[slot].stop()
        playing[slot] = false
        updateState(slot, if (urls[slot].isBlank()) StreamState.NOT_SET else StreamState.PAUSED)
    }

    fun stopAll(clearUrls: Boolean = false) {
        widgets.indices.forEach { slot ->
            widgets[slot].stop()
            playing[slot] = false
            if (clearUrls) urls[slot] = ""
            updateState(slot, if (urls[slot].isBlank()) StreamState.NOT_SET else StreamState.PAUSED)
        }
    }

    fun currentUrl(slot: Int): String = urls.getOrNull(slot).orEmpty()

    fun configuredUrls(): List<String> = urls.toList()

    fun state(slot: Int): StreamState = states.getOrElse(slot) { StreamState.NOT_SET }

    fun liveCount(): Int = states.count { it == StreamState.LIVE }

    private fun updateState(slot: Int, state: StreamState) {
        states[slot] = state
        labels[slot].post {
            labels[slot].text = "${names[slot]}  ·  ${state.displayText}"
            labels[slot].setTextColor(Color.parseColor(state.textColor))
            onStateChanged(slot, state)
        }
    }

    private val StreamState.displayText: String
        get() = when (this) {
            StreamState.NOT_SET -> "NOT SET"
            StreamState.PAUSED -> "PAUSED"
            StreamState.CONNECTING -> "CONNECTING"
            StreamState.LIVE -> "LIVE"
            StreamState.OFFLINE -> "OFFLINE"
        }

    private val StreamState.textColor: String
        get() = when (this) {
            StreamState.LIVE -> "#2FB344"
            StreamState.CONNECTING -> "#F59F00"
            StreamState.OFFLINE -> "#E03131"
            StreamState.PAUSED, StreamState.NOT_SET -> "#9AA8B6"
        }
}
