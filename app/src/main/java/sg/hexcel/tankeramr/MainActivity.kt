package sg.hexcel.tankeramr

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.skydroid.rcsdk.KeyManager
import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.rcsdk.SDKManagerCallBack
import com.skydroid.rcsdk.common.DeviceType
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith
import com.skydroid.rcsdk.common.callback.KeyListener
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.key.RemoteControllerKey
import kotlinx.coroutines.launch
import sg.hexcel.tankeramr.control.TurnButton
import sg.hexcel.tankeramr.control.TurnPacket
import sg.hexcel.tankeramr.control.UdpCommandSender
import sg.hexcel.tankeramr.network.CameraScanner
import sg.hexcel.tankeramr.video.VideoGridController

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var videoGrid: VideoGridController
    private lateinit var udpTargetButton: Button
    private lateinit var speedBubbles: List<TextView>

    private val udpSender = UdpCommandSender(host = DEFAULT_JETSON_IP, port = DEFAULT_UDP_PORT)

    private val rcPollHandler = Handler(Looper.getMainLooper())
    private var rcPollingEnabled = false
    private var rcConnected = false
    private var lastSpeedLevel = -1
    private var lastRcFailureLogTimeMs = 0L

    private val h16ChannelsListener: KeyListener<IntArray> = KeyListener { _, newValue ->
        updateSpeedFromChannels(newValue)
    }

    private val rcPollRunnable = object : Runnable {
        override fun run() {
            if (!rcPollingEnabled) return

            pollRcChannelsOnce()
            rcPollHandler.postDelayed(this, RC_CHANNEL_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.tvStatus)
        udpTargetButton = findViewById(R.id.btnUdpTarget)
        videoGrid = VideoGridController(
            widgets = listOf(findViewById(R.id.fpv0), findViewById(R.id.fpv1), findViewById(R.id.fpv2), findViewById(R.id.fpv3)),
            labels = listOf(findViewById(R.id.tvCam0), findViewById(R.id.tvCam1), findViewById(R.id.tvCam2), findViewById(R.id.tvCam3))
        )

        speedBubbles = listOf(
            findViewById(R.id.speedBubble0),
            findViewById(R.id.speedBubble1),
            findViewById(R.id.speedBubble2),
            findViewById(R.id.speedBubble3),
            findViewById(R.id.speedBubble4),
            findViewById(R.id.speedBubble5)
        )
        updateSpeedBubbleUi(0)

        udpSender.start(this) { setStatus(it) }
        updateUdpTargetButton()

        bindUdpTargetButton()
        bindVideoButtons()
        bindCameraTapToReplace()

        bindTurnButton(findViewById(R.id.btnL2), TurnButton.L2_LEFT_ACW)
        bindTurnButton(findViewById(R.id.btnL1), TurnButton.L1_CENTER_ACW)
        bindTurnButton(findViewById(R.id.btnR1), TurnButton.R1_CENTER_CW)
        bindTurnButton(findViewById(R.id.btnR2), TurnButton.R2_RIGHT_CW)

        initRcSdkForSpeedDisplay()

        setStatus("UDP ready: ${udpSender.targetText()} | Video ready | Speed 0")
    }

    private fun bindUdpTargetButton() {
        udpTargetButton.setOnClickListener {
            val ipInput = EditText(this)
            ipInput.hint = "Jetson IP"
            ipInput.setSingleLine(true)
            ipInput.setText(udpSender.hostText())
            ipInput.inputType = InputType.TYPE_CLASS_TEXT
            ipInput.setSelectAllOnFocus(true)

            AlertDialog.Builder(this)
                .setTitle("Set TankerAMR UDP target")
                .setMessage("Activation commands will be sent to this Jetson IP on UDP port $DEFAULT_UDP_PORT.")
                .setView(ipInput)
                .setPositiveButton("Confirm") { _, _ ->
                    val host = ipInput.text.toString().trim()
                    if (host.isBlank()) {
                        setStatus("UDP target not changed: empty IP")
                        return@setPositiveButton
                    }
                    udpSender.setTarget(host, DEFAULT_UDP_PORT)
                    udpSender.start(this) { setStatus(it) }
                    updateUdpTargetButton()
                    setStatus("UDP target set: ${udpSender.targetText()}")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateUdpTargetButton() {
        udpTargetButton.text = "UDP\n${udpSender.hostText()}"
    }

    private fun bindVideoButtons() {
        findViewById<Button>(R.id.btnDefaultC12).setOnClickListener {
            videoGrid.play(0, "rtsp://192.168.144.108:554/stream=0")
            setStatus("CAM 1 playing C12 default stream")
        }

        findViewById<Button>(R.id.btnStopVideo).setOnClickListener {
            videoGrid.stopAll(clearLabels = true)
            setStatus("Stopped all video streams")
        }

        findViewById<Button>(R.id.btnManualUrls).setOnClickListener {
            val input = EditText(this)
            input.minLines = 4
            input.setSingleLine(false)
            input.setText(CameraScanner.defaultFourCameraUrls().joinToString("\n"))
            AlertDialog.Builder(this)
                .setTitle("Enter 1 to 4 RTSP URLs")
                .setMessage("Default uses JINGYANG/XM stream=0 RTSP URLs. Blank lines are ignored.")
                .setView(input)
                .setPositiveButton("Play") { _, _ ->
                    val urls = input.text.toString()
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    videoGrid.playAllReplacing(urls)
                    setStatus("Playing ${urls.take(4).size} stream(s) from manual URLs")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scanAndChooseCamera { chosen ->
                chooseSlotThenPlay(chosen.primaryRtspUrl, chosen.ip)
            }
        }
    }

    private fun bindCameraTapToReplace() {
        val tapTargets = listOf(
            listOf<View>(findViewById(R.id.frameCam0), findViewById(R.id.fpv0), findViewById(R.id.tvCam0)),
            listOf<View>(findViewById(R.id.frameCam1), findViewById(R.id.fpv1), findViewById(R.id.tvCam1)),
            listOf<View>(findViewById(R.id.frameCam2), findViewById(R.id.fpv2), findViewById(R.id.tvCam2)),
            listOf<View>(findViewById(R.id.frameCam3), findViewById(R.id.fpv3), findViewById(R.id.tvCam3))
        )
        tapTargets.forEachIndexed { slot, views ->
            views.forEach { view ->
                view.setOnClickListener {
                    scanAndChooseCamera { chosen ->
                        val current = videoGrid.currentUrl(slot)
                        if (current == chosen.primaryRtspUrl) {
                            setStatus("CAM ${slot + 1} already uses ${chosen.ip}; unchanged")
                        } else {
                            videoGrid.play(slot, chosen.primaryRtspUrl)
                            setStatus("CAM ${slot + 1} replaced with ${chosen.ip}")
                        }
                    }
                }
            }
        }
    }

    private fun scanAndChooseCamera(onCameraChosen: (CameraScanner.HostCandidate) -> Unit) {
        setStatus("Scanning 192.168.144.0/24 for IP cameras...")
        lifecycleScope.launch {
            val result = CameraScanner().scanSubnet("192.168.144")
            if (result.isEmpty()) {
                setStatus("No camera hosts found. Check subnet, camera power, and Ethernet wiring.")
                return@launch
            }
            val labels = result.map { candidate ->
                "${candidate.ip}   ports=${candidate.openPorts}"
            }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select IP camera")
                .setItems(labels) { _, which ->
                    onCameraChosen(result[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
            setStatus("Scan complete: ${result.size} IP camera host(s) found")
        }
    }

    private fun chooseSlotThenPlay(rtspUrl: String, ip: String) {
        val slots = arrayOf("CAM 1", "CAM 2", "CAM 3", "CAM 4")
        AlertDialog.Builder(this)
            .setTitle("Place $ip into which CAM view?")
            .setItems(slots) { _, which ->
                videoGrid.play(which, rtspUrl)
                setStatus("${slots[which]} playing $ip")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindTurnButton(button: Button, turnButton: TurnButton) {
        button.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    udpSender.send(TurnPacket.down(turnButton))
                    setStatus("${turnButton.shortName} DOWN → ${udpSender.targetText()}")
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    udpSender.send(TurnPacket.up(turnButton))
                    true
                }
                else -> true
            }
        }
    }

    private fun initRcSdkForSpeedDisplay() {
        try {
            RCSDKManager.setMainThreadCallBack(true)
            RCSDKManager.initSDK(this, object : SDKManagerCallBack {
                override fun onRcConnected() {
                    rcConnected = true
                    setStatus("UDP ready: ${udpSender.targetText()} | RC connected | Speed ${currentSpeedText()}")
                    startSpeedChannelPolling()
                }

                override fun onRcConnectFail(e: SkyException?) {
                    rcConnected = false
                    Log.w(TAG, "RCSDK connect failed: $e")
                    setStatus("UDP ready: ${udpSender.targetText()} | RCSDK connect failed | Speed ${currentSpeedText()}")
                }

                override fun onRcDisconnect() {
                    rcConnected = false
                    stopSpeedChannelPolling()
                    Log.w(TAG, "RCSDK disconnected")
                    setStatus("UDP ready: ${udpSender.targetText()} | RC disconnected | Speed ${currentSpeedText()}")
                }
            })
            RCSDKManager.connectToRC()
        } catch (e: Exception) {
            Log.e(TAG, "RCSDK init/connect failed", e)
            setStatus("UDP ready: ${udpSender.targetText()} | RCSDK unavailable | Speed ${currentSpeedText()}")
        }
    }

    private fun startSpeedChannelPolling() {
        stopSpeedChannelPolling()
        rcPollingEnabled = true

        try {
            if (RCSDKManager.getDeviceType() == DeviceType.H16) {
                KeyManager.cancelListen(h16ChannelsListener)
                KeyManager.listen(RemoteControllerKey.KeyH16Channels, h16ChannelsListener)
            } else {
                rcPollHandler.post(rcPollRunnable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RC channel polling", e)
        }
    }

    private fun stopSpeedChannelPolling() {
        rcPollingEnabled = false
        rcPollHandler.removeCallbacks(rcPollRunnable)

        try {
            KeyManager.cancelListen(h16ChannelsListener)
        } catch (_: Exception) {
        }
    }

    private fun pollRcChannelsOnce() {
        try {
            KeyManager.get(RemoteControllerKey.KeyChannels, object : CompletionCallbackWith<IntArray> {
                override fun onSuccess(value: IntArray?) {
                    updateSpeedFromChannels(value)
                }

                override fun onFailure(e: SkyException) {
                    val now = System.currentTimeMillis()
                    if (now - lastRcFailureLogTimeMs > RC_FAILURE_LOG_INTERVAL_MS) {
                        lastRcFailureLogTimeMs = now
                        Log.w(TAG, "Failed to read RC channels: $e")
                    }
                }
            })
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastRcFailureLogTimeMs > RC_FAILURE_LOG_INTERVAL_MS) {
                lastRcFailureLogTimeMs = now
                Log.w(TAG, "RC channel poll exception", e)
            }
        }
    }

    private fun updateSpeedFromChannels(channels: IntArray?) {
        if (channels == null || channels.size < 12) return

        val ch12 = channels[11]
        val level = speedLevelFromCh12(ch12)

        runOnUiThread {
            updateSpeedBubbleUi(level)
        }
    }

    private fun speedLevelFromCh12(ch12: Int): Int {
        val clamped = ch12.coerceIn(CH_MIN, CH_MAX)
        val sectionWidth = (CH_MAX - CH_MIN).toFloat() / SPEED_LEVEL_COUNT.toFloat()

        return ((clamped - CH_MIN) / sectionWidth).toInt().coerceIn(0, SPEED_LEVEL_COUNT - 1)
    }

    private fun updateSpeedBubbleUi(level: Int) {
        val activeLevel = level.coerceIn(0, SPEED_LEVEL_COUNT - 1)

        if (activeLevel == lastSpeedLevel && speedBubbles.isNotEmpty()) return

        lastSpeedLevel = activeLevel

        speedBubbles.forEachIndexed { index, view ->
            val active = index == activeLevel
            view.background = makeSpeedBubbleBackground(active)
            view.setTextColor(if (active) Color.WHITE else Color.LTGRAY)
            view.text = index.toString()
        }
    }

    private fun makeSpeedBubbleBackground(active: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.rgb(106, 53, 255) else Color.rgb(48, 48, 48))
            setStroke(
                if (active) 3 else 1,
                if (active) Color.WHITE else Color.rgb(120, 120, 120)
            )
        }
    }

    private fun currentSpeedText(): String {
        return if (lastSpeedLevel >= 0) lastSpeedLevel.toString() else "0"
    }

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpeedChannelPolling()

        try {
            RCSDKManager.disconnectRC()
        } catch (_: Exception) {
        }

        videoGrid.stopAll(clearLabels = true)
        udpSender.close()
    }

    companion object {
        private const val TAG = "TankerMainActivity"

        private const val DEFAULT_JETSON_IP = "192.168.144.20"
        private const val DEFAULT_UDP_PORT = 5005

        private const val CH_MIN = 1050
        private const val CH_MAX = 1950
        private const val SPEED_LEVEL_COUNT = 6

        // Skydroid demo notes that H12/H12Pro/H30 channel values are GET-based,
        // so they must be polled. Keep this close to their recommended 100 ms.
        private const val RC_CHANNEL_POLL_INTERVAL_MS = 120L
        private const val RC_FAILURE_LOG_INTERVAL_MS = 2000L
    }
}
