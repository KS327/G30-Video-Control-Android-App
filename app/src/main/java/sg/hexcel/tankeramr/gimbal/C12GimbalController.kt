package sg.hexcel.tankeramr.gimbal

import android.os.SystemClock
import android.util.Log
import com.skydroid.rcsdk.PayloadManager
import com.skydroid.rcsdk.comm.CommListener
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.common.payload.C12
import com.skydroid.rcsdk.common.payload.PayloadType
import kotlin.math.abs
import kotlin.math.sign

/** Owns the C12 UDP session and safely arbitrates the shared RC channels. */
class C12GimbalController(private val onStateChanged: (State) -> Unit) {
    enum class State(val operatorText: String) {
        DISCONNECTED("GIMBAL OFFLINE"), DRIVE_LOCKED("GIMBAL LOCKED"),
        WAITING_NEUTRAL("CENTER STICKS"), READY("GIMBAL READY"),
        ACTIVE("GIMBAL ACTIVE"), RC_LOST("RC SIGNAL LOST"), ERROR("GIMBAL FAULT")
    }

    private var payload: C12? = null
    private var appActive = false
    private var rcConnected = false
    private var gimbalChannelsEnabled = false
    private var waitingForNeutral = true
    private var neutralSinceMs: Long? = null
    private var lastCommandMs = 0L
    private var lastChannelsMs = 0L
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var state = State.DISCONNECTED

    fun start() {
        if (appActive) return
        appActive = true
        try {
            val c12 = (PayloadManager.getUDPPayload(
                PayloadType.C12, LOCAL_UDP_PORT, C12_IP, C12_CONTROL_PORT
            ) as? C12) ?: error("RCSDK did not create a C12 payload")
            payload = c12
            c12.setCommListener(object : CommListener {
                override fun onConnectSuccess() = publishGateState()
                override fun onConnectFail(e: SkyException?) {
                    Log.e(TAG, "C12 UDP connection failed", e)
                    publish(State.ERROR)
                }
                override fun onDisconnect() = publish(State.DISCONNECTED)
                override fun onReadData(data: ByteArray?) = Unit
            })
            PayloadManager.connectPayload(c12)
            publishGateState()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize C12 control", e)
            publish(State.ERROR)
        }
    }

    fun stop() {
        if (!appActive) return
        appActive = false
        sendStop(force = true)
        payload?.let { runCatching { PayloadManager.disconnectPayload(it) } }
        payload = null
        resetGate()
        publish(State.DISCONNECTED)
    }

    fun setRcConnected(connected: Boolean) {
        rcConnected = connected
        if (!connected) {
            sendStop(force = true)
            resetGate()
        }
        publishGateState()
    }

    fun updateChannels(channels: IntArray) {
        if (channels.size < REQUIRED_CHANNEL_COUNT || !appActive || !rcConnected) {
            sendStop(force = true)
            return
        }
        lastChannelsMs = SystemClock.elapsedRealtime()
        val enabledNow = channels[CH06_INDEX] <= CH06_DRIVE_LOCK_THRESHOLD
        if (!enabledNow) {
            if (gimbalChannelsEnabled || lastYaw != 0f || lastPitch != 0f) sendStop(force = true)
            gimbalChannelsEnabled = false
            waitingForNeutral = true
            neutralSinceMs = null
            publish(State.DRIVE_LOCKED)
            return
        }
        if (!gimbalChannelsEnabled) {
            gimbalChannelsEnabled = true
            waitingForNeutral = true
            neutralSinceMs = null
            sendStop(force = true)
        }

        val yaw = channelToRate(channels[CH13_INDEX])
        val pitch = channelToRate(channels[CH14_INDEX])
        val now = SystemClock.elapsedRealtime()
        if (waitingForNeutral) {
            if (yaw == 0f && pitch == 0f) {
                val since = neutralSinceMs ?: now.also { neutralSinceMs = it }
                if (now - since >= NEUTRAL_HANDOVER_MS) waitingForNeutral = false
            } else neutralSinceMs = null
            if (waitingForNeutral) {
                publish(State.WAITING_NEUTRAL)
                return
            }
        }
        if (now - lastCommandMs < MIN_COMMAND_INTERVAL_MS) return
        lastCommandMs = now
        sendRate(yaw, pitch)
    }

    /** Stops persistent rate commands if the RCSDK channel stream becomes stale. */
    fun tick() {
        if (!appActive || !rcConnected || lastChannelsMs == 0L) return
        if (SystemClock.elapsedRealtime() - lastChannelsMs <= CHANNEL_WATCHDOG_MS) return
        sendStop(force = true)
        resetGate()
        lastChannelsMs = 0L
        publish(State.RC_LOST)
    }

    fun center(): Boolean {
        val c12 = payload ?: return false
        if (!appActive || !rcConnected || !gimbalChannelsEnabled || waitingForNeutral ||
            lastYaw != 0f || lastPitch != 0f) return false
        return runCatching {
            c12.gotoYawPitch(0f, 0f)
            true
        }.onFailure {
            Log.e(TAG, "Unable to center C12", it)
            publish(State.ERROR)
        }.getOrDefault(false)
    }

    private fun sendRate(yaw: Float, pitch: Float) {
        val c12 = payload ?: return publish(State.DISCONNECTED)
        runCatching {
            c12.controlYawPitch(yaw, pitch)
            lastYaw = yaw
            lastPitch = pitch
            publish(if (yaw == 0f && pitch == 0f) State.READY else State.ACTIVE)
        }.onFailure {
            Log.e(TAG, "Unable to control C12", it)
            publish(State.ERROR)
        }
    }

    private fun sendStop(force: Boolean) {
        if (!force && lastYaw == 0f && lastPitch == 0f) return
        runCatching { payload?.controlYawPitch(0f, 0f) }
            .onFailure { Log.w(TAG, "Unable to stop C12", it) }
        lastYaw = 0f
        lastPitch = 0f
    }

    private fun resetGate() {
        gimbalChannelsEnabled = false
        waitingForNeutral = true
        neutralSinceMs = null
        lastChannelsMs = 0L
    }

    private fun publishGateState() {
        publish(when {
            !appActive || payload?.isConnected() != true || !rcConnected -> State.DISCONNECTED
            !gimbalChannelsEnabled -> State.DRIVE_LOCKED
            waitingForNeutral -> State.WAITING_NEUTRAL
            lastYaw != 0f || lastPitch != 0f -> State.ACTIVE
            else -> State.READY
        })
    }

    private fun publish(next: State) {
        if (state == next) return
        state = next
        onStateChanged(next)
    }

    companion object {
        private const val TAG = "C12GimbalController"
        const val C12_IP = "192.168.144.108"
        private const val C12_CONTROL_PORT = 5000
        private const val LOCAL_UDP_PORT = 0
        private const val REQUIRED_CHANNEL_COUNT = 14
        private const val CH06_INDEX = 5
        private const val CH13_INDEX = 12
        private const val CH14_INDEX = 13
        private const val CH06_DRIVE_LOCK_THRESHOLD = 1725
        private const val CHANNEL_MIN = 1050f
        private const val CHANNEL_CENTER = 1500f
        private const val CHANNEL_MAX = 1950f
        private const val CHANNEL_DEADBAND = 65f
        private const val MAX_GIMBAL_RATE = 40f
        private const val MIN_COMMAND_INTERVAL_MS = 50L
        private const val NEUTRAL_HANDOVER_MS = 300L
        private const val CHANNEL_WATCHDOG_MS = 500L

        internal fun channelToRate(raw: Int): Float {
            val offset = raw.coerceIn(CHANNEL_MIN.toInt(), CHANNEL_MAX.toInt()) - CHANNEL_CENTER
            val magnitude = abs(offset)
            if (magnitude <= CHANNEL_DEADBAND) return 0f
            val usableRange = CHANNEL_MAX - CHANNEL_CENTER - CHANNEL_DEADBAND
            val normalized = ((magnitude - CHANNEL_DEADBAND) / usableRange).coerceIn(0f, 1f)
            return normalized * MAX_GIMBAL_RATE * offset.sign
        }
    }
}
