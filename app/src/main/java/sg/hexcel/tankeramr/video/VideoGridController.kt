package sg.hexcel.tankeramr.video

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val desiredPlaying = MutableList(widgets.size) { false }
    private val states = MutableList(widgets.size) { StreamState.NOT_SET }
    private val playGeneration = MutableList(widgets.size) { 0 }
    private val attemptStartedAt = MutableList(widgets.size) { 0L }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var primarySlot = 0

    init {
        require(widgets.size == labels.size && widgets.size == names.size)
        widgets.forEachIndexed { slot, widget ->
            widget.onPlayerStateListener = object : OnPlayerStateListener {
                override fun onConnected() {
                    mainHandler.post {
                        if (desiredPlaying[slot] && playing[slot]) {
                            updateState(slot, StreamState.LIVE)
                        }
                    }
                }

                override fun onDisconnect() {
                    mainHandler.post {
                        handleDisconnect(slot)
                    }
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
            stopPlayer(slot, desired = false)
            updateState(slot, StreamState.NOT_SET)
            return
        }

        if (playNow) {
            if (changed || !playing[slot]) play(slot)
        } else {
            stopPlayer(slot, desired = false)
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
        desiredPlaying[slot] = true
        playGeneration[slot] += 1
        val generation = playGeneration[slot]
        attemptStartedAt[slot] = SystemClock.elapsedRealtime()
        val widget = widgets[slot]
        playing[slot] = false
        widget.stop()
        // C12 thermal is an unusual 384x288 HEVC profile that the G30 hardware
        // decoder may reject. It is low resolution, so software decoding is
        // inexpensive and more compatible; keep the visible primary feed accelerated.
        val isC12Thermal = urls[slot].contains(":555/")
        widget.usingMediaCodec = slot == primarySlot && !isC12Thermal
        widget.url = urls[slot]
        widget.playerType = PlayerType.AUTO
        // Use interleaved RTSP/TCP for deterministic delivery through the
        // receiver and Ethernet switch. AUTO may select UDP, where packet loss
        // produces corrupted HEVC frames and the C12 can fail to negotiate.
        widget.rtspTranstype = RtspTransport.TCP
        widget.rcType = RCSDKUtils.getDeviceType().value
        widget.isThreadMaxPriority = slot == primarySlot
        playing[slot] = true
        updateState(slot, StreamState.CONNECTING)
        widget.start()
        mainHandler.postDelayed({
            if (desiredPlaying[slot] && playing[slot] &&
                playGeneration[slot] == generation && states[slot] != StreamState.LIVE) {
                stopFailedAttempt(slot)
            }
        }, CONNECTION_TIMEOUT_MS)
    }

    fun playVisible(visibleSlots: Set<Int>) {
        widgets.indices.forEach { slot ->
            if (slot in visibleSlots && urls[slot].isNotBlank()) {
                if (!desiredPlaying[slot]) play(slot)
            } else if (desiredPlaying[slot] || playing[slot]) {
                pause(slot)
            }
        }
    }

    fun pause(slot: Int) {
        if (slot !in widgets.indices) return
        stopPlayer(slot, desired = false)
        updateState(slot, if (urls[slot].isBlank()) StreamState.NOT_SET else StreamState.PAUSED)
    }

    fun stopAll(clearUrls: Boolean = false) {
        widgets.indices.forEach { slot ->
            stopPlayer(slot, desired = false)
            if (clearUrls) urls[slot] = ""
            updateState(slot, if (urls[slot].isBlank()) StreamState.NOT_SET else StreamState.PAUSED)
        }
    }

    fun currentUrl(slot: Int): String = urls.getOrNull(slot).orEmpty()

    fun configuredUrls(): List<String> = urls.toList()

    fun state(slot: Int): StreamState = states.getOrElse(slot) { StreamState.NOT_SET }

    fun liveCount(): Int = states.count { it == StreamState.LIVE }

    private fun stopFailedAttempt(slot: Int) {
        if (!desiredPlaying[slot] || !playing[slot]) return
        stopPlayer(slot, desired = true)
        updateState(slot, StreamState.OFFLINE)
    }

    private fun handleDisconnect(slot: Int) {
        if (!desiredPlaying[slot] || !playing[slot]) return
        val generation = playGeneration[slot]
        val elapsed = SystemClock.elapsedRealtime() - attemptStartedAt[slot]
        if (elapsed >= DISCONNECT_GRACE_MS) {
            stopFailedAttempt(slot)
            return
        }

        // stop() from the previous URL may report its disconnect after the new
        // URL has started. Let the replacement stream connect before deciding.
        mainHandler.postDelayed({
            if (desiredPlaying[slot] && playing[slot] &&
                playGeneration[slot] == generation && states[slot] != StreamState.LIVE) {
                stopFailedAttempt(slot)
            }
        }, DISCONNECT_GRACE_MS - elapsed)
    }

    private fun stopPlayer(slot: Int, desired: Boolean) {
        desiredPlaying[slot] = desired
        playing[slot] = false
        playGeneration[slot] += 1
        widgets[slot].stop()
    }

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

    companion object {
        // The Skydroid player retries internally. Stop a broken stream before
        // it can starve telemetry, controls, and the other camera decoders.
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val DISCONNECT_GRACE_MS = 3_000L
    }
}
