package sg.hexcel.tankeramr

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
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
    private enum class ViewMode { FOCUS, FOUR, SIX, FULLSCREEN }
    private enum class C12Mode { VISIBLE, THERMAL }

    private lateinit var status: TextView
    private lateinit var cameraWall: ConstraintLayout
    private lateinit var cameraFrames: List<FrameLayout>
    private lateinit var cameraGestureLayers: List<View>
    private lateinit var viewModeButton: Button
    private lateinit var c12ModeButton: Button
    private lateinit var videoButton: Button
    private lateinit var udpTargetButton: Button
    private lateinit var speedBubbles: List<TextView>
    private lateinit var videoGrid: VideoGridController
    private lateinit var preferences: SharedPreferences

    private var currentViewMode = ViewMode.FOCUS
    private var previousViewMode = ViewMode.FOCUS
    private var focusedCameraSlot = 0
    private var c12Mode = C12Mode.VISIBLE
    private var videoStoppedByOperator = false

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

        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        status = findViewById(R.id.tvStatus)
        cameraWall = findViewById(R.id.cameraWall)
        viewModeButton = findViewById(R.id.btnViewMode)
        c12ModeButton = findViewById(R.id.btnC12Mode)
        videoButton = findViewById(R.id.btnStopVideo)
        udpTargetButton = findViewById(R.id.btnUdpTarget)

        cameraFrames = listOf(
            findViewById(R.id.frameCam0), findViewById(R.id.frameCam1),
            findViewById(R.id.frameCam2), findViewById(R.id.frameCam3),
            findViewById(R.id.frameCam4), findViewById(R.id.frameCam5)
        )
        cameraGestureLayers = listOf(
            findViewById(R.id.gestureCam0), findViewById(R.id.gestureCam1),
            findViewById(R.id.gestureCam2), findViewById(R.id.gestureCam3),
            findViewById(R.id.gestureCam4), findViewById(R.id.gestureCam5)
        )

        videoGrid = VideoGridController(
            widgets = listOf(
                findViewById(R.id.fpv0), findViewById(R.id.fpv1),
                findViewById(R.id.fpv2), findViewById(R.id.fpv3),
                findViewById(R.id.fpv4), findViewById(R.id.fpv5)
            ),
            labels = listOf(
                findViewById(R.id.tvCam0), findViewById(R.id.tvCam1),
                findViewById(R.id.tvCam2), findViewById(R.id.tvCam3),
                findViewById(R.id.tvCam4), findViewById(R.id.tvCam5)
            ),
            names = CAMERA_NAMES,
            onStateChanged = { _, _ -> refreshStatusSummary() }
        )

        speedBubbles = listOf(
            findViewById(R.id.speedBubble0), findViewById(R.id.speedBubble1),
            findViewById(R.id.speedBubble2), findViewById(R.id.speedBubble3),
            findViewById(R.id.speedBubble4), findViewById(R.id.speedBubble5),
            findViewById(R.id.speedBubble6)
        )
        updateSpeedBubbleUi(0)

        restoreOperatorState()
        videoGrid.setUrls(loadCameraUrls())
        bindViewModeButton()
        bindVideoButtons()
        bindCameraGestures()
        bindUdpTargetButton()
        bindBackNavigation()

        bindTurnButton(findViewById(R.id.btnL2), TurnButton.L2_LEFT_ACW)
        bindTurnButton(findViewById(R.id.btnL1), TurnButton.L1_CENTER_ACW)
        bindTurnButton(findViewById(R.id.btnR1), TurnButton.R1_CENTER_CW)
        bindTurnButton(findViewById(R.id.btnR2), TurnButton.R2_RIGHT_CW)

        udpSender.start(this) { setStatus(it) }
        updateC12Button()
        applyViewMode(currentViewMode)
        initRcSdkForSpeedDisplay()
        refreshStatusSummary()
    }

    private fun restoreOperatorState() {
        focusedCameraSlot = preferences.getInt(PREF_FOCUSED_CAMERA, 0).coerceIn(0, CAMERA_COUNT - 1)
        currentViewMode = preferences.getString(PREF_VIEW_MODE, ViewMode.FOCUS.name)
            ?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == ViewMode.FULLSCREEN }
            ?: ViewMode.FOCUS
        previousViewMode = currentViewMode
        c12Mode = preferences.getString(PREF_C12_MODE, C12Mode.VISIBLE.name)
            ?.let { runCatching { C12Mode.valueOf(it) }.getOrNull() }
            ?: C12Mode.VISIBLE
    }

    private fun loadCameraUrls(): List<String> {
        val defaults = CameraScanner.defaultSixCameraUrls().toMutableList()
        defaults[0] = c12Url()
        return defaults.mapIndexed { index, default ->
            preferences.getString(cameraUrlKey(index), default) ?: default
        }.toMutableList().also { it[0] = c12Url() }
    }

    private fun saveCameraUrls(urls: List<String>) {
        preferences.edit().apply {
            urls.take(CAMERA_COUNT).forEachIndexed { index, url -> putString(cameraUrlKey(index), url.trim()) }
        }.apply()
    }

    private fun bindViewModeButton() {
        viewModeButton.setOnClickListener {
            val modes = arrayOf("Focus 1 + 5", "Four cameras (2 x 2)", "All six cameras (3 x 2)")
            AlertDialog.Builder(this)
                .setTitle("Camera view")
                .setItems(modes) { _, which ->
                    val selected = when (which) {
                        1 -> ViewMode.FOUR
                        2 -> ViewMode.SIX
                        else -> ViewMode.FOCUS
                    }
                    previousViewMode = selected
                    preferences.edit().putString(PREF_VIEW_MODE, selected.name).apply()
                    applyViewMode(selected)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun bindVideoButtons() {
        c12ModeButton.setOnClickListener {
            c12Mode = if (c12Mode == C12Mode.VISIBLE) C12Mode.THERMAL else C12Mode.VISIBLE
            preferences.edit().putString(PREF_C12_MODE, c12Mode.name).apply()
            updateC12Button()
            val shouldPlay = !videoStoppedByOperator && 0 in visibleSlots(currentViewMode)
            videoGrid.setUrl(0, c12Url(), playNow = shouldPlay)
            val urls = videoGrid.configuredUrls()
            saveCameraUrls(urls)
            setStatus("FRONT C12 switching to ${c12Mode.name}")
        }

        videoButton.setOnClickListener {
            videoStoppedByOperator = !videoStoppedByOperator
            if (videoStoppedByOperator) {
                videoGrid.stopAll(clearUrls = false)
                videoButton.text = "VIDEO START"
                setStatus("All camera streams paused by operator")
            } else {
                videoButton.text = "VIDEO STOP"
                videoGrid.playVisible(visibleSlots(currentViewMode))
                setStatus("Camera streams starting")
            }
        }

        findViewById<Button>(R.id.btnManualUrls).setOnClickListener { showCameraUrlEditor() }
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scanAndChooseCamera { chosen -> chooseSlotThenPlay(chosen.primaryRtspUrl, chosen.ip) }
        }
    }

    private fun showCameraUrlEditor() {
        val input = EditText(this).apply {
            minLines = CAMERA_COUNT
            setSingleLine(false)
            setText(videoGrid.configuredUrls().joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Six camera endpoints")
            .setMessage("One RTSP URL per line. Line 1 is FRONT C12; blank lines keep unused slots visible as NOT SET.")
            .setView(input)
            .setPositiveButton("Save and play") { _, _ ->
                val urls = input.text.toString().lines().map { it.trim() }.take(CAMERA_COUNT).toMutableList()
                while (urls.size < CAMERA_COUNT) urls.add("")
                urls[0] = c12Url()
                saveCameraUrls(urls)
                videoGrid.setUrls(urls)
                if (!videoStoppedByOperator) videoGrid.playVisible(visibleSlots(currentViewMode))
                setStatus("Saved ${urls.count { it.isNotBlank() }} configured camera endpoints")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindCameraGestures() {
        cameraGestureLayers.forEachIndexed { slot, layer ->
            val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    focusCamera(slot)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentViewMode == ViewMode.FULLSCREEN) {
                        exitFullscreen()
                    } else {
                        focusedCameraSlot = slot
                        preferences.edit().putInt(PREF_FOCUSED_CAMERA, slot).apply()
                        previousViewMode = currentViewMode
                        applyViewMode(ViewMode.FULLSCREEN)
                    }
                    return true
                }
            })
            layer.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        }
    }

    private fun focusCamera(slot: Int) {
        focusedCameraSlot = slot.coerceIn(0, CAMERA_COUNT - 1)
        preferences.edit().putInt(PREF_FOCUSED_CAMERA, focusedCameraSlot).apply()
        videoGrid.setPrimarySlot(focusedCameraSlot)
        if (currentViewMode == ViewMode.FOCUS) {
            applyViewMode(ViewMode.FOCUS)
        } else {
            refreshCameraSelection()
            setStatus("${CAMERA_NAMES[focusedCameraSlot]} selected")
        }
    }

    private fun bindBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentViewMode == ViewMode.FULLSCREEN) {
                    exitFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun exitFullscreen() {
        applyViewMode(previousViewMode.takeUnless { it == ViewMode.FULLSCREEN } ?: ViewMode.FOCUS)
    }

    private fun applyViewMode(mode: ViewMode) {
        currentViewMode = mode
        videoGrid.setPrimarySlot(focusedCameraSlot)
        val constraints = ConstraintSet().apply { clone(cameraWall) }

        cameraFrames.forEach { frame ->
            constraints.clear(frame.id)
            constraints.constrainWidth(frame.id, ConstraintSet.MATCH_CONSTRAINT)
            constraints.constrainHeight(frame.id, ConstraintSet.MATCH_CONSTRAINT)
            constraints.setVisibility(frame.id, View.GONE)
        }

        when (mode) {
            ViewMode.FOCUS -> applyFocusConstraints(constraints)
            ViewMode.FOUR -> applyFourCameraConstraints(constraints)
            ViewMode.SIX -> applySixCameraConstraints(constraints)
            ViewMode.FULLSCREEN -> place(
                constraints, cameraFrames[focusedCameraSlot].id,
                ConstraintSet.PARENT_ID, ConstraintSet.LEFT,
                ConstraintSet.PARENT_ID, ConstraintSet.RIGHT,
                ConstraintSet.PARENT_ID, ConstraintSet.TOP,
                ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM
            )
        }

        constraints.applyTo(cameraWall)
        refreshCameraSelection()
        updateViewModeButton()
        if (!videoStoppedByOperator) videoGrid.playVisible(visibleSlots(mode))
        refreshStatusSummary()
    }

    private fun applyFocusConstraints(constraints: ConstraintSet) {
        place(
            constraints, cameraFrames[focusedCameraSlot].id,
            ConstraintSet.PARENT_ID, ConstraintSet.LEFT,
            R.id.guideVFocus, ConstraintSet.RIGHT,
            ConstraintSet.PARENT_ID, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM
        )
        val thumbnails = cameraFrames.indices.filter { it != focusedCameraSlot }
        val tops = listOf(ConstraintSet.PARENT_ID, R.id.guideH20, R.id.guideH40, R.id.guideH60, R.id.guideH80)
        val bottoms = listOf(R.id.guideH20, R.id.guideH40, R.id.guideH60, R.id.guideH80, ConstraintSet.PARENT_ID)
        thumbnails.forEachIndexed { index, slot ->
            place(
                constraints, cameraFrames[slot].id,
                R.id.guideVFocus, ConstraintSet.LEFT,
                ConstraintSet.PARENT_ID, ConstraintSet.RIGHT,
                tops[index], if (tops[index] == ConstraintSet.PARENT_ID) ConstraintSet.TOP else ConstraintSet.BOTTOM,
                bottoms[index], if (bottoms[index] == ConstraintSet.PARENT_ID) ConstraintSet.BOTTOM else ConstraintSet.TOP
            )
        }
    }

    private fun applyFourCameraConstraints(constraints: ConstraintSet) {
        val positions = listOf(
            intArrayOf(ConstraintSet.PARENT_ID, R.id.guideVHalf, ConstraintSet.PARENT_ID, R.id.guideHHalf),
            intArrayOf(R.id.guideVHalf, ConstraintSet.PARENT_ID, ConstraintSet.PARENT_ID, R.id.guideHHalf),
            intArrayOf(ConstraintSet.PARENT_ID, R.id.guideVHalf, R.id.guideHHalf, ConstraintSet.PARENT_ID),
            intArrayOf(R.id.guideVHalf, ConstraintSet.PARENT_ID, R.id.guideHHalf, ConstraintSet.PARENT_ID)
        )
        positions.forEachIndexed { slot, p ->
            place(
                constraints, cameraFrames[slot].id,
                p[0], if (p[0] == ConstraintSet.PARENT_ID) ConstraintSet.LEFT else ConstraintSet.RIGHT,
                p[1], if (p[1] == ConstraintSet.PARENT_ID) ConstraintSet.RIGHT else ConstraintSet.LEFT,
                p[2], if (p[2] == ConstraintSet.PARENT_ID) ConstraintSet.TOP else ConstraintSet.BOTTOM,
                p[3], if (p[3] == ConstraintSet.PARENT_ID) ConstraintSet.BOTTOM else ConstraintSet.TOP
            )
        }
    }

    private fun applySixCameraConstraints(constraints: ConstraintSet) {
        val lefts = intArrayOf(ConstraintSet.PARENT_ID, R.id.guideVOneThird, R.id.guideVTwoThird)
        val rights = intArrayOf(R.id.guideVOneThird, R.id.guideVTwoThird, ConstraintSet.PARENT_ID)
        cameraFrames.indices.forEach { slot ->
            val column = slot % 3
            val topRow = slot < 3
            place(
                constraints, cameraFrames[slot].id,
                lefts[column], if (column == 0) ConstraintSet.LEFT else ConstraintSet.RIGHT,
                rights[column], if (column == 2) ConstraintSet.RIGHT else ConstraintSet.LEFT,
                if (topRow) ConstraintSet.PARENT_ID else R.id.guideHHalf,
                if (topRow) ConstraintSet.TOP else ConstraintSet.BOTTOM,
                if (topRow) R.id.guideHHalf else ConstraintSet.PARENT_ID,
                if (topRow) ConstraintSet.TOP else ConstraintSet.BOTTOM
            )
        }
    }

    private fun place(
        constraints: ConstraintSet,
        viewId: Int,
        leftTarget: Int,
        leftSide: Int,
        rightTarget: Int,
        rightSide: Int,
        topTarget: Int,
        topSide: Int,
        bottomTarget: Int,
        bottomSide: Int
    ) {
        constraints.setVisibility(viewId, View.VISIBLE)
        constraints.connect(viewId, ConstraintSet.LEFT, leftTarget, leftSide, dp(2))
        constraints.connect(viewId, ConstraintSet.RIGHT, rightTarget, rightSide, dp(2))
        constraints.connect(viewId, ConstraintSet.TOP, topTarget, topSide, dp(2))
        constraints.connect(viewId, ConstraintSet.BOTTOM, bottomTarget, bottomSide, dp(2))
    }

    private fun visibleSlots(mode: ViewMode): Set<Int> = when (mode) {
        ViewMode.FOCUS, ViewMode.SIX -> cameraFrames.indices.toSet()
        ViewMode.FOUR -> setOf(0, 1, 2, 3)
        ViewMode.FULLSCREEN -> setOf(focusedCameraSlot)
    }

    private fun refreshCameraSelection() {
        cameraFrames.forEachIndexed { index, frame ->
            frame.setBackgroundResource(
                if (index == focusedCameraSlot) R.drawable.bg_camera_tile_selected else R.drawable.bg_camera_tile
            )
        }
    }

    private fun updateViewModeButton() {
        viewModeButton.text = when (currentViewMode) {
            ViewMode.FOCUS -> "VIEW · FOCUS"
            ViewMode.FOUR -> "VIEW · 4 CAM"
            ViewMode.SIX -> "VIEW · 6 CAM"
            ViewMode.FULLSCREEN -> "VIEW · FULL"
        }
    }

    private fun c12Url(): String = when (c12Mode) {
        C12Mode.VISIBLE -> CameraScanner.C12_VISIBLE_URL
        C12Mode.THERMAL -> CameraScanner.C12_THERMAL_URL
    }

    private fun updateC12Button() {
        c12ModeButton.text = c12Mode.name
    }

    private fun bindUdpTargetButton() {
        udpTargetButton.setOnClickListener {
            val ipInput = EditText(this).apply {
                hint = "Jetson IP"
                setSingleLine(true)
                setText(udpSender.hostText())
                inputType = InputType.TYPE_CLASS_TEXT
                setSelectAllOnFocus(true)
            }
            AlertDialog.Builder(this)
                .setTitle("LOCAL Jetson endpoint")
                .setMessage("Commands use UDP port $DEFAULT_UDP_PORT.")
                .setView(ipInput)
                .setPositiveButton("Save") { _, _ ->
                    val host = ipInput.text.toString().trim()
                    if (host.isBlank()) {
                        setStatus("Jetson endpoint unchanged: empty address")
                    } else {
                        udpSender.setTarget(host, DEFAULT_UDP_PORT)
                        udpSender.start(this) { setStatus(it) }
                        setStatus("LOCAL endpoint set to ${udpSender.targetText()}")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun scanAndChooseCamera(onCameraChosen: (CameraScanner.HostCandidate) -> Unit) {
        setStatus("Scanning 192.168.144.0/24 for camera services")
        lifecycleScope.launch {
            val result = CameraScanner().scanSubnet("192.168.144")
            if (result.isEmpty()) {
                setStatus("No camera services found on 192.168.144.0/24")
                return@launch
            }
            val labels = result.map { "${it.ip}   ports=${it.openPorts}" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select camera")
                .setItems(labels) { _, which -> onCameraChosen(result[which]) }
                .setNegativeButton("Cancel", null)
                .show()
            setStatus("Scan complete: ${result.size} camera host(s)")
        }
    }

    private fun chooseSlotThenPlay(rtspUrl: String, ip: String) {
        AlertDialog.Builder(this)
            .setTitle("Assign $ip")
            .setItems(CAMERA_NAMES.toTypedArray()) { _, which ->
                if (which == 0 && ip != CameraScanner.C12_IP) {
                    setStatus("FRONT C12 remains fixed at ${CameraScanner.C12_IP}")
                    return@setItems
                }
                val urls = videoGrid.configuredUrls().toMutableList()
                urls[which] = if (which == 0) c12Url() else rtspUrl
                saveCameraUrls(urls)
                videoGrid.setUrl(which, urls[which], !videoStoppedByOperator && which in visibleSlots(currentViewMode))
                setStatus("${CAMERA_NAMES[which]} assigned to $ip")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindTurnButton(button: Button, turnButton: TurnButton) {
        button.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    udpSender.send(TurnPacket.down(turnButton))
                    setStatus("${turnButton.shortName} DOWN -> ${udpSender.targetText()}")
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
                    startSpeedChannelPolling()
                    refreshStatusSummary()
                }

                override fun onRcConnectFail(e: SkyException?) {
                    rcConnected = false
                    Log.w(TAG, "RCSDK connect failed: $e")
                    refreshStatusSummary()
                }

                override fun onRcDisconnect() {
                    rcConnected = false
                    stopSpeedChannelPolling()
                    refreshStatusSummary()
                }
            })
            RCSDKManager.connectToRC()
        } catch (e: Exception) {
            rcConnected = false
            Log.e(TAG, "RCSDK init/connect failed", e)
            refreshStatusSummary()
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
        runCatching { KeyManager.cancelListen(h16ChannelsListener) }
    }

    private fun pollRcChannelsOnce() {
        try {
            KeyManager.get(RemoteControllerKey.KeyChannels, object : CompletionCallbackWith<IntArray> {
                override fun onSuccess(value: IntArray?) = updateSpeedFromChannels(value)

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
        val level = speedLevelFromCh12(channels[11])
        runOnUiThread { updateSpeedBubbleUi(level) }
    }

    private fun speedLevelFromCh12(ch12: Int): Int {
        val clamped = ch12.coerceIn(CH_MIN, CH_MAX)
        val sectionWidth = (CH_MAX - CH_MIN).toFloat() / SPEED_LEVEL_COUNT
        return ((clamped - CH_MIN) / sectionWidth).toInt().coerceIn(0, SPEED_LEVEL_COUNT - 1)
    }

    private fun updateSpeedBubbleUi(level: Int) {
        val activeLevel = level.coerceIn(0, SPEED_LEVEL_COUNT - 1)
        if (activeLevel == lastSpeedLevel) return
        lastSpeedLevel = activeLevel
        speedBubbles.forEachIndexed { index, view ->
            val active = index == activeLevel
            view.setBackgroundResource(if (active) R.drawable.bg_speed_bubble_active else R.drawable.bg_speed_bubble_inactive)
            view.setTextColor(ContextCompat.getColor(this, if (active) R.color.white else R.color.industrial_text_secondary))
        }
    }

    private fun refreshStatusSummary() {
        if (!::status.isInitialized || !::videoGrid.isInitialized) return
        runOnUiThread {
            val rc = if (rcConnected) "RC ONLINE" else "RC OFFLINE"
            val view = when (currentViewMode) {
                ViewMode.FOCUS -> "FOCUS"
                ViewMode.FOUR -> "4 CAM"
                ViewMode.SIX -> "6 CAM"
                ViewMode.FULLSCREEN -> "FULL"
            }
            status.text = "$rc   |   CAMERAS ${videoGrid.liveCount()}/$CAMERA_COUNT LIVE   |   $view   |   ${udpSender.targetText()}"
        }
    }

    private fun setStatus(text: String) {
        if (::status.isInitialized) runOnUiThread { status.text = text }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopSpeedChannelPolling()
        runCatching { RCSDKManager.disconnectRC() }
        if (::videoGrid.isInitialized) videoGrid.stopAll(clearUrls = false)
        udpSender.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TankerMainActivity"
        private const val DEFAULT_JETSON_IP = "192.168.144.20"
        private const val DEFAULT_UDP_PORT = 5005
        private const val CAMERA_COUNT = 6
        private val CAMERA_NAMES = listOf("FRONT C12", "CAMERA 2", "CAMERA 3", "CAMERA 4", "CAMERA 5", "CAMERA 6")

        private const val PREFERENCES_NAME = "tanker_operator_settings"
        private const val PREF_VIEW_MODE = "camera_view_mode"
        private const val PREF_FOCUSED_CAMERA = "focused_camera"
        private const val PREF_C12_MODE = "c12_mode"
        private fun cameraUrlKey(slot: Int) = "camera_url_$slot"

        private const val CH_MIN = 1050
        private const val CH_MAX = 1950
        private const val SPEED_LEVEL_COUNT = 7
        private const val RC_CHANNEL_POLL_INTERVAL_MS = 100L
        private const val RC_FAILURE_LOG_INTERVAL_MS = 2000L
    }
}
