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
import android.widget.Toast
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
import com.skydroid.rcsdk.common.remotecontroller.ChannelSettings
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith
import com.skydroid.rcsdk.common.callback.KeyListener
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.key.RemoteControllerKey
import kotlinx.coroutines.launch
import org.json.JSONObject
import sg.hexcel.tankeramr.control.TurnButton
import sg.hexcel.tankeramr.control.Ch05Position
import sg.hexcel.tankeramr.control.ConnectionRoute
import sg.hexcel.tankeramr.control.ControlProtocol
import sg.hexcel.tankeramr.control.OperatorControl
import sg.hexcel.tankeramr.control.OperatorSnapshot
import sg.hexcel.tankeramr.control.SidexState
import sg.hexcel.tankeramr.control.UdpCommandSender
import sg.hexcel.tankeramr.control.UdpTelemetryReceiver
import sg.hexcel.tankeramr.gimbal.C12GimbalController
import sg.hexcel.tankeramr.network.CameraScanner
import sg.hexcel.tankeramr.video.VideoGridController

class MainActivity : AppCompatActivity() {
    private enum class ViewMode { FOCUS, FOUR, SIX, FULLSCREEN }
    private enum class C12Mode { VISIBLE, THERMAL }
    private enum class PendingSidexAction { START, STOP }

    private lateinit var status: TextView
    private lateinit var cameraWall: ConstraintLayout
    private lateinit var cameraFrames: List<FrameLayout>
    private lateinit var cameraGestureLayers: List<View>
    private lateinit var viewModeButton: Button
    private lateinit var c12ModeButton: Button
    private lateinit var c12CenterButton: Button
    private lateinit var c12GimbalState: TextView
    private lateinit var videoButton: Button
    private lateinit var udpTargetButton: Button
    private lateinit var controlState: TextView
    private lateinit var telemetry: TextView
    private lateinit var turnQueue: TextView
    private lateinit var zeroTelemetryButton: Button
    private lateinit var internetArmButton: Button
    private lateinit var sidexDemoButton: Button
    private lateinit var collisionMonitorButton: Button
    private lateinit var sidexMapButton: Button
    private lateinit var speedBubbles: List<TextView>
    private lateinit var videoGrid: VideoGridController
    private lateinit var preferences: SharedPreferences

    private var currentViewMode = ViewMode.FOCUS
    private var previousViewMode = ViewMode.FOCUS
    private var focusedCameraSlot = 0
    private var c12Mode = C12Mode.VISIBLE
    private var videoStoppedByOperator = false

    private val udpSender = UdpCommandSender(host = DEFAULT_JETSON_IP, port = DEFAULT_UDP_PORT)
    private val telemetryReceiver = UdpTelemetryReceiver(TELEMETRY_UDP_PORT)
    private val operatorControl = OperatorControl(sidexAvailableByDefault = BuildConfig.CONTROL_SIMULATOR)
    private var operatorSnapshot = OperatorSnapshot()
    private var lastActiveTurn: TurnButton? = null
    private var commandSequence = 0L
    private var debugCh05Override: Int? = null
    private var pendingSidexAction: PendingSidexAction? = null
    private var lastSidexCompletionSignature: String? = null
    private var lastSidexFaultSignature: String? = null
    private var c12FallbackAttempted = false
    private var c12OfflineNotified = false
    private var pendingCollisionMonitorState: Boolean? = null
    private var collisionCommandDeadlineMs = 0L
    private lateinit var c12Gimbal: C12GimbalController

    private val operatorTickRunnable = object : Runnable {
        override fun run() {
            if (::c12Gimbal.isInitialized) c12Gimbal.tick()
            val snapshot = operatorControl.tick()
            renderOperatorState(snapshot)
            if (BuildConfig.CONTROL_SIMULATOR) handleSidexCompletion(snapshot)
            rcPollHandler.postDelayed(this, OPERATOR_TICK_MS)
        }
    }

    private val rcPollHandler = Handler(Looper.getMainLooper())
    private var rcPollingEnabled = false
    private var rcConnected = false
    private var lastSpeedLevel = -1
    private var lastRcFailureLogTimeMs = 0L
    private var lastRcChannelsLogTimeMs = 0L

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
        udpSender.setTarget(localJetsonHost(), DEFAULT_UDP_PORT)
        status = findViewById(R.id.tvStatus)
        cameraWall = findViewById(R.id.cameraWall)
        viewModeButton = findViewById(R.id.btnViewMode)
        c12ModeButton = findViewById(R.id.btnC12Mode)
        c12CenterButton = findViewById(R.id.btnC12Center)
        c12GimbalState = findViewById(R.id.tvC12GimbalState)
        videoButton = findViewById(R.id.btnStopVideo)
        udpTargetButton = findViewById(R.id.btnUdpTarget)
        controlState = findViewById(R.id.tvControlState)
        telemetry = findViewById(R.id.tvTelemetry)
        turnQueue = findViewById(R.id.tvTurnQueue)
        zeroTelemetryButton = findViewById(R.id.btnZeroTelemetry)
        internetArmButton = findViewById(R.id.btnInternetArm)
        sidexDemoButton = findViewById(R.id.btnSidexDemo)
        collisionMonitorButton = findViewById(R.id.btnCollisionMonitor)
        sidexMapButton = findViewById(R.id.btnSidexTeach)

        cameraFrames = listOf(
            findViewById(R.id.frameCam0), findViewById(R.id.frameCam1),
            findViewById(R.id.frameCam2), findViewById(R.id.frameCam3)
        )
        cameraGestureLayers = listOf(
            findViewById(R.id.gestureCam0), findViewById(R.id.gestureCam1),
            findViewById(R.id.gestureCam2), findViewById(R.id.gestureCam3)
        )
        findViewById<FrameLayout>(R.id.frameCam4).visibility = View.GONE
        findViewById<FrameLayout>(R.id.frameCam5).visibility = View.GONE

        videoGrid = VideoGridController(
            widgets = listOf(
                findViewById(R.id.fpv0), findViewById(R.id.fpv1),
                findViewById(R.id.fpv2), findViewById(R.id.fpv3)
            ),
            labels = listOf(
                findViewById(R.id.tvCam0), findViewById(R.id.tvCam1),
                findViewById(R.id.tvCam2), findViewById(R.id.tvCam3)
            ),
            names = CAMERA_NAMES,
            onStateChanged = { slot, state ->
                if (slot == 0) handleC12StreamState(state)
                refreshStatusSummary()
            }
        )

        speedBubbles = listOf(
            findViewById(R.id.speedBubble0), findViewById(R.id.speedBubble1),
            findViewById(R.id.speedBubble2), findViewById(R.id.speedBubble3),
            findViewById(R.id.speedBubble4), findViewById(R.id.speedBubble5),
            findViewById(R.id.speedBubble6)
        )
        updateSpeedBubbleUi(0)
        c12Gimbal = C12GimbalController { state ->
            runOnUiThread { renderC12GimbalState(state) }
        }

        restoreOperatorState()
        videoGrid.setUrls(loadCameraUrls())
        bindViewModeButton()
        bindVideoButtons()
        bindC12GimbalControls()
        bindCameraGestures()
        bindUdpTargetButton()
        bindOperatorControls()
        bindDebugSimulatorControls()
        bindBackNavigation()

        bindTurnButton(findViewById(R.id.btnL2), TurnButton.L2_LEFT_ACW)
        bindTurnButton(findViewById(R.id.btnL1), TurnButton.L1_CENTER_ACW)
        bindTurnButton(findViewById(R.id.btnR1), TurnButton.R1_CENTER_CW)
        bindTurnButton(findViewById(R.id.btnR2), TurnButton.R2_RIGHT_CW)

        udpSender.start(this) { setStatus(it) }
        telemetryReceiver.start(
            onTelemetry = { packet -> runOnUiThread {
                val snapshot = operatorControl.updateRemote(packet)
                renderOperatorState(snapshot)
                handleSidexCompletion(snapshot)
            } },
            onError = { setStatus(it) }
        )
        rcPollHandler.post(operatorTickRunnable)
        updateC12Button()
        applyViewMode(currentViewMode)
        initRcSdkForSpeedDisplay()
        refreshStatusSummary()
    }

    private fun restoreOperatorState() {
        focusedCameraSlot = preferences.getInt(PREF_FOCUSED_CAMERA, 0).coerceIn(0, CAMERA_COUNT - 1)
        currentViewMode = preferences.getString(PREF_VIEW_MODE, ViewMode.FOCUS.name)
            ?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == ViewMode.FULLSCREEN || it == ViewMode.SIX }
            ?: ViewMode.FOCUS
        previousViewMode = currentViewMode
        // Deterministic operator startup: always begin with the visible C12 feed.
        // THERMAL remains available as a session toggle but is never restored after relaunch.
        c12Mode = C12Mode.VISIBLE
        migrateCameraConfiguration()
    }

    private fun migrateCameraConfiguration() {
        if (preferences.getInt(PREF_CAMERA_SCHEMA, 0) >= CAMERA_SCHEMA_VERSION) return
        val defaults = CameraScanner.defaultCameraUrls()
        val existing = (1..3).map { preferences.getString(cameraUrlKey(it), null)?.trim().orEmpty() }
        val knownAssignments = existing.map { url ->
            CameraScanner.VERIFIED_IP_CAMERA_IPS.firstOrNull { ip -> url.contains(ip) }
        }
        val onlyKnownOrBlank = existing.zip(knownAssignments).all { (url, ip) -> url.isBlank() || ip != null }
        val knownLayoutIsInvalid = knownAssignments.any { it == null } ||
            knownAssignments.filterNotNull().distinct().size != CameraScanner.VERIFIED_IP_CAMERA_IPS.size
        preferences.edit().apply {
            // Repair duplicate/missing assignments only when all saved entries belong to
            // the known TankerAMR camera set. Unrelated custom RTSP URLs are preserved.
            for (slot in 1..3) {
                val key = cameraUrlKey(slot)
                val current = existing[slot - 1]
                val migrated = if (onlyKnownOrBlank && knownLayoutIsInvalid) defaults[slot]
                else if (current.isBlank()) defaults[slot]
                else CameraScanner.preferMainStream(current)
                putString(key, migrated)
            }
            remove(cameraUrlKey(4))
            remove(cameraUrlKey(5))
            putInt(PREF_CAMERA_SCHEMA, CAMERA_SCHEMA_VERSION)
        }.apply()
    }

    private fun loadCameraUrls(): List<String> {
        val defaults = CameraScanner.defaultCameraUrls().toMutableList()
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
            val modes = arrayOf("Focus 1 + 3", "All four cameras (2 x 2)")
            AlertDialog.Builder(this)
                .setTitle("Camera view")
                .setItems(modes) { _, which ->
                    val selected = when (which) {
                        1 -> ViewMode.FOUR
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
            c12FallbackAttempted = c12Mode == C12Mode.THERMAL
            c12OfflineNotified = false
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

    private fun bindC12GimbalControls() {
        c12CenterButton.setOnClickListener {
            if (c12Gimbal.center()) {
                setStatus("C12 returning to centre")
                Toast.makeText(this, "C12 centring", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Centre CH13/CH14 and set CH06 middle or down",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        renderC12GimbalState(C12GimbalController.State.DISCONNECTED)
    }

    private fun renderC12GimbalState(state: C12GimbalController.State) {
        c12GimbalState.text = state.operatorText
        c12CenterButton.isEnabled = state == C12GimbalController.State.READY
        c12GimbalState.setTextColor(ContextCompat.getColor(this, when (state) {
            C12GimbalController.State.READY,
            C12GimbalController.State.ACTIVE -> R.color.industrial_success
            C12GimbalController.State.WAITING_NEUTRAL -> R.color.industrial_warning
            C12GimbalController.State.RC_LOST,
            C12GimbalController.State.ERROR -> R.color.industrial_danger
            else -> R.color.industrial_text_secondary
        }))
    }

    private fun showCameraUrlEditor() {
        val input = EditText(this).apply {
            minLines = CAMERA_COUNT
            setSingleLine(false)
            setText(videoGrid.configuredUrls().joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Four camera endpoints")
            .setMessage("One RTSP URL per line: C12, front middle, back left, and back right.")
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
            .setNeutralButton("Restore verified") { _, _ -> restoreVerifiedCameraLayout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreVerifiedCameraLayout() {
        val urls = CameraScanner.defaultCameraUrls().toMutableList().also { it[0] = c12Url() }
        saveCameraUrls(urls)
        preferences.edit().putInt(PREF_CAMERA_SCHEMA, CAMERA_SCHEMA_VERSION).apply()
        videoGrid.setUrls(urls)
        if (!videoStoppedByOperator) videoGrid.playVisible(visibleSlots(currentViewMode))
        setStatus("Restored C12 and three verified IP cameras")
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
            ViewMode.SIX -> applyFourCameraConstraints(constraints)
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
            ViewMode.SIX -> "VIEW · 4 CAM"
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

    private fun handleC12StreamState(state: VideoGridController.StreamState) {
        if (state == VideoGridController.StreamState.LIVE) {
            c12OfflineNotified = false
            return
        }
        if (state != VideoGridController.StreamState.OFFLINE || videoStoppedByOperator) return

        if (c12Mode == C12Mode.VISIBLE && !c12FallbackAttempted) {
            c12FallbackAttempted = true
            c12Mode = C12Mode.THERMAL
            updateC12Button()
            videoGrid.setUrl(0, c12Url(), playNow = 0 in visibleSlots(currentViewMode))
            Toast.makeText(this, "C12 visible unavailable; trying thermal", Toast.LENGTH_LONG).show()
        } else if (!c12OfflineNotified) {
            c12OfflineNotified = true
            Toast.makeText(this, "C12 unavailable; check or restart the C12 camera", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindUdpTargetButton() {
        udpTargetButton.setOnClickListener {
            when (operatorSnapshot.ch05) {
                Ch05Position.UP_RF_SIDEX -> showDestinationEditor(
                    title = "Local Jetson destination",
                    preferenceKey = PREF_LOCAL_HOST,
                    defaultHost = DEFAULT_JETSON_IP,
                    destinationName = "LOCAL"
                )
                Ch05Position.DOWN_INTERNET -> showDestinationEditor(
                    title = "Tailscale Jetson destination",
                    preferenceKey = PREF_INTERNET_HOST,
                    defaultHost = DEFAULT_TAILSCALE_IP,
                    destinationName = "TAILSCALE"
                )
                Ch05Position.MIDDLE_SAFE, Ch05Position.UNKNOWN ->
                    setStatus("Choose destination with CH05 up or down")
            }
        }
    }

    private fun localJetsonHost(): String =
        preferences.getString(PREF_LOCAL_HOST, DEFAULT_JETSON_IP)?.trim().orEmpty()
            .ifBlank { DEFAULT_JETSON_IP }

    private fun internetJetsonHost(): String =
        preferences.getString(PREF_INTERNET_HOST, DEFAULT_TAILSCALE_IP)?.trim().orEmpty()
            .ifBlank { DEFAULT_TAILSCALE_IP }

    private fun showDestinationEditor(
        title: String,
        preferenceKey: String,
        defaultHost: String,
        destinationName: String
    ) {
        val input = EditText(this).apply {
            hint = "Jetson IP address"
            setSingleLine(true)
            setText(preferences.getString(preferenceKey, defaultHost)?.trim().orEmpty().ifBlank { defaultHost })
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Default: $defaultHost · UDP $DEFAULT_UDP_PORT")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNeutralButton("Test", null)
            .setNegativeButton("Default", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = input.text.toString().trim()
                if (host.isBlank()) setStatus("$destinationName destination unchanged: address is empty")
                else {
                    preferences.edit().putString(preferenceKey, host).apply()
                    udpSender.setTarget(host, DEFAULT_UDP_PORT)
                    setStatus("$destinationName destination saved: $host:$DEFAULT_UDP_PORT")
                    renderOperatorState(operatorControl.snapshot())
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val host = input.text.toString().trim()
                if (host.isBlank()) setStatus("Enter an IP address before testing")
                else {
                    udpSender.sendTo(host, DEFAULT_UDP_PORT, ControlProtocol.command(++commandSequence, "ping"))
                    setStatus("Testing $destinationName destination: $host:$DEFAULT_UDP_PORT")
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                preferences.edit().putString(preferenceKey, defaultHost).apply()
                input.setText(defaultHost)
                udpSender.setTarget(defaultHost, DEFAULT_UDP_PORT)
                setStatus("$destinationName destination restored: $defaultHost:$DEFAULT_UDP_PORT")
                renderOperatorState(operatorControl.snapshot())
            }
        }
        dialog.show()
    }

    private fun bindOperatorControls() {
        zeroTelemetryButton.setOnClickListener {
            val snapshot = operatorControl.snapshot()
            if (snapshot.telemetrySource == "STALE") {
                setStatus("ZERO TELEMETRY requires fresh Jetson telemetry")
            } else if (kotlin.math.abs(snapshot.speedMetresPerSecond ?: Double.POSITIVE_INFINITY) > 0.03) {
                setStatus("Stop TankerAMR before zeroing telemetry")
            } else if (OperatorControl.isSidexActive(snapshot.sidexState) || snapshot.sidexArmed) {
                setStatus("Stop SIDEX DEMO before zeroing telemetry")
            } else if (snapshot.ch05 == Ch05Position.DOWN_INTERNET && snapshot.internetArmed) {
                setStatus("Disarm INTERNET control before zeroing telemetry")
            } else if (snapshot.ch05 == Ch05Position.UNKNOWN) {
                setStatus("ZERO TELEMETRY requires a valid CH05 signal")
            } else {
                sendProductionCommandReliable("telemetry_zero")
                setStatus("Zeroing speed, roll, pitch and yaw at current pose")
            }
        }
        internetArmButton.setOnClickListener {
            val result = operatorControl.toggleInternetArm()
            renderOperatorState(result)
            if (result.message == "INTERNET ARMED" || result.message == "INTERNET DISARMED") {
                sendProductionCommand("internet_arm", JSONObject().put("armed", result.internetArmed))
            }
        }
        sidexDemoButton.setOnClickListener {
            when (operatorSnapshot.sidexState) {
                SidexState.STOPPED, SidexState.COMPLETE, SidexState.FAULT -> confirmStartSidex()
                SidexState.STARTING, SidexState.RUNNING, SidexState.PAUSED -> confirmStopSidex()
            }
        }
        collisionMonitorButton.setOnClickListener {
            val snapshot = operatorControl.snapshot()
            if (!OperatorControl.isSidexActive(snapshot.sidexState)) {
                setStatus("Start SIDEX DEMO before changing collision monitoring")
            } else {
                val desired = !snapshot.collisionMonitorActive
                pendingCollisionMonitorState = desired
                collisionCommandDeadlineMs = System.currentTimeMillis() + 2500L
                val action = if (desired) "collision_on" else "collision_off"
                if (!BuildConfig.CONTROL_SIMULATOR) {
                    val packets = List(3) {
                        ControlProtocol.sidex(++commandSequence, action)
                    }
                    udpSender.sendBurst(packets, intervalMs = 120L)
                }
                setStatus(if (desired) "Requesting collision monitoring ON" else "Requesting collision monitoring OFF")
            }
        }
        sidexMapButton.setOnClickListener { showMapControls() }
    }

    private fun showMapControls() {
        val snapshot = operatorControl.snapshot()
        if (!snapshot.mapRecording) {
            if (snapshot.ch05 != Ch05Position.UP_RF_SIDEX) {
                setStatus("MAP COURSE requires CH05 up for manual RF driving")
                return
            }
            if (!snapshot.mapAvailable) {
                setStatus("MAP COURSE backend is not ready")
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Map SiDex course?")
                .setMessage(
                    "Place Tanker at the marked start. Keep CH05 up and manually drive one slow, " +
                        "clear stair/ramp lap. Return to the same mark before saving. Autonomous drive remains disarmed."
                )
                .setPositiveButton("Start scan") { _, _ -> requestSidexMap("start") }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Course scan active")
            .setMessage(
                "Recorded: %.0f seconds\nReturn Tanker to the marked start and stop before Save Map."
                    .format(snapshot.mapDurationSeconds)
            )
            .setPositiveButton("Save map") { _, _ -> requestSidexMap("save") }
            .setNegativeButton("Cancel scan") { _, _ -> requestSidexMap("cancel") }
            .show()
    }

    private fun requestSidexMap(action: String) {
        if (!BuildConfig.CONTROL_SIMULATOR) {
            udpSender.send(ControlProtocol.sidexMap(++commandSequence, action))
        }
        setStatus("MAP COURSE ${action.uppercase()} submitted")
    }

    private fun bindDebugSimulatorControls() {
        if (!BuildConfig.CONTROL_SIMULATOR) return
        controlState.setOnClickListener {
            val choices = arrayOf(
                "UP · RF + SIDEX",
                "MIDDLE · SAFE",
                "DOWN · INTERNET",
                "USE PHYSICAL RCSDK VALUE"
            )
            AlertDialog.Builder(this)
                .setTitle("SIM CH05 · no Tanker commands")
                .setItems(choices) { _, which ->
                    debugCh05Override = when (which) {
                        0 -> CH_MAX
                        1 -> (CH_MIN + CH_MAX) / 2
                        2 -> CH_MIN
                        else -> null
                    }
                    debugCh05Override?.let { simulated ->
                        val channels = IntArray(16) { (CH_MIN + CH_MAX) / 2 }.also { it[4] = simulated }
                        renderOperatorState(operatorControl.updateChannels(channels))
                    }
                    setStatus(if (debugCh05Override == null) "Using physical RCSDK CH05" else "SIM CH05 override active")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmStartSidex() {
        val result = operatorControl.snapshot()
        if (result.ch05 != Ch05Position.UP_RF_SIDEX) {
            setStatus("SIDEX DEMO requires CH05 up")
            return
        }
        if (!result.sidexAvailable) {
            setStatus("SIDEX backend is not ready")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Start SiDex Demo?")
            .setMessage(
                "The Tanker will use the saved 3D course map, LiDAR collision monitoring and IMU stage checks. " +
                    "It will approach, climb, turn left, descend and return through three ground turns. Keep CH05 up."
            )
            .setPositiveButton("Start") { _, _ ->
                val started = operatorControl.toggleSidexDemo()
                renderOperatorState(started)
                if (started.sidexState == SidexState.STARTING) requestSidexDemo(PendingSidexAction.START)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmStopSidex() {
        val paused = operatorControl.snapshot().sidexState == SidexState.PAUSED
        AlertDialog.Builder(this)
            .setTitle("Stop SiDex Demo?")
            .setMessage(
                if (paused) {
                    "The Tanker is paused for an obstacle and will resume automatically after the path is clear. " +
                        "Choose Stop only if you want to end the complete SiDex loop. CH05 middle remains the immediate safety stop."
                } else {
                    "This ends the complete SiDex loop. Use CH05 middle for an immediate safety stop."
                }
            )
            .setPositiveButton("Stop demo") { _, _ ->
                val result = operatorControl.toggleSidexDemo()
                renderOperatorState(result)
                if (result.sidexState == SidexState.STOPPED) {
                    requestSidexDemo(PendingSidexAction.STOP)
                }
            }
            .setNegativeButton("Keep running", null)
            .show()
    }

    private fun requestSidexDemo(action: PendingSidexAction) {
        pendingSidexAction = action
        val wireAction = if (action == PendingSidexAction.START) "start" else "stop"
        if (!BuildConfig.CONTROL_SIMULATOR) {
            udpSender.send(
                ControlProtocol.sidex(
                    ++commandSequence,
                    wireAction,
                    operatorConfirmed = action == PendingSidexAction.STOP
                )
            )
        }
        setStatus(if (action == PendingSidexAction.START) "Starting SIDEX DEMO; waiting for Jetson"
            else "Stopping SIDEX DEMO")
    }

    private fun handleSidexCompletion(snapshot: OperatorSnapshot) {
        if (snapshot.sidexState == SidexState.FAULT) {
            val signature = "${snapshot.sidexFaultCode}:${snapshot.sidexStage}:${snapshot.message}"
            if (lastSidexFaultSignature != signature) {
                lastSidexFaultSignature = signature
                pendingSidexAction = null
                AlertDialog.Builder(this)
                    .setTitle("SiDex Demo Stopped")
                    .setMessage(sidexFaultMessage(snapshot))
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }
        if (snapshot.sidexState == SidexState.STARTING || snapshot.sidexState == SidexState.RUNNING) {
            lastSidexFaultSignature = null
        }
        if (snapshot.sidexState == SidexState.COMPLETE) {
            val signature = "${snapshot.sidexStage}:${snapshot.sidexLap}"
            if (lastSidexCompletionSignature != signature) {
                lastSidexCompletionSignature = signature
                pendingSidexAction = null
                val distance = snapshot.sidexStageDistanceMetres
                    ?.let { "\nMeasured stage distance: %.3f m".format(it) }
                    .orEmpty()
                val closure = snapshot.alignmentErrorMetres
                    ?.let { "\nReturn-to-start error: %.3f m".format(it) }
                    .orEmpty()
                AlertDialog.Builder(this)
                    .setTitle("SiDex Stage Completed")
                    .setMessage("${snapshot.sidexStage.replace('_', ' ')} completed.$distance$closure")
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }
        if (snapshot.sidexState == SidexState.STARTING || snapshot.sidexState == SidexState.RUNNING) {
            lastSidexCompletionSignature = null
        }
        when (pendingSidexAction) {
            PendingSidexAction.START -> when (snapshot.sidexState) {
                SidexState.RUNNING, SidexState.PAUSED -> {
                    pendingSidexAction = null
                    setStatus("SIDEX DEMO running: ${snapshot.sidexStage}")
                }
                SidexState.FAULT -> {
                    pendingSidexAction = null
                    AlertDialog.Builder(this).setTitle("SIDEX DEMO start failed")
                        .setMessage(snapshot.message).setPositiveButton("OK", null).show()
                }
                else -> Unit
            }
            PendingSidexAction.STOP -> if (
                snapshot.sidexState == SidexState.STOPPED || snapshot.sidexState == SidexState.COMPLETE
            ) {
                pendingSidexAction = null
                setStatus("SIDEX DEMO stopped; LiDAR telemetry remains active")
            }
            null -> Unit
        }
    }

    private fun sidexFaultMessage(snapshot: OperatorSnapshot): String {
        return when (snapshot.sidexFaultCode) {
            "OBSTACLE_TIMEOUT" ->
                "An obstacle stayed in the route for 30 seconds. The demo stopped. " +
                    "Remove the obstacle, check the route, then start the demo again."
            "STAIR_CONTACT_MISSING" ->
                "The Tanker did not detect the stair climb. Check that it is centred and facing the stair entrance."
            "TOP_STOP_OVERSHOOT" ->
                "The Tanker passed the expected top-platform stopping position. Return it to the marked start before retrying."
            "SAFETY_VIEW_LOST" ->
                "The LiDAR collision view was unavailable. Check the LiDAR status and route, then retry."
            "LOCALIZATION_LOST", "START_NOT_LOCALIZED", "LAP_RELOCALIZATION_TIMEOUT" ->
                "The Tanker could not confirm its course position. Put it at the marked start and wait for MAP LOCALIZED."
            "TELEMETRY_LOST" ->
                "Telemetry from the Jetson was lost. Check the Jetson and LiDAR connection before retrying."
            else -> snapshot.message.ifBlank {
                "The demo stopped at ${snapshot.sidexStage.replace('_', ' ')}. Check the displayed fault and route before retrying."
            }
        }
    }

    private fun sendProductionCommand(type: String, payload: JSONObject = JSONObject()) {
        if (!BuildConfig.CONTROL_SIMULATOR) udpSender.send(ControlProtocol.command(++commandSequence, type, payload))
    }

    private fun sendProductionCommandReliable(type: String, payload: JSONObject = JSONObject()) {
        if (BuildConfig.CONTROL_SIMULATOR) return
        val packets = List(3) {
            ControlProtocol.command(++commandSequence, type, payload)
        }
        udpSender.sendBurst(packets, intervalMs = 120L)
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
        button.setOnClickListener {
            if (BuildConfig.CONTROL_SIMULATOR) {
                renderOperatorState(operatorControl.enqueueTurn(turnButton))
            } else {
                udpSender.send(ControlProtocol.turn(++commandSequence, turnButton))
                setStatus("${turnButton.shortName} turn request sent")
            }
        }
    }

    private fun initRcSdkForSpeedDisplay() {
        try {
            RCSDKManager.setMainThreadCallBack(true)
            RCSDKManager.initSDK(this, object : SDKManagerCallBack {
                override fun onRcConnected() {
                    rcConnected = true
                    c12Gimbal.setRcConnected(true)
                    logRcChannelSettings()
                    startSpeedChannelPolling()
                    refreshStatusSummary()
                }

                override fun onRcConnectFail(e: SkyException?) {
                    rcConnected = false
                    c12Gimbal.setRcConnected(false)
                    Log.w(TAG, "RCSDK connect failed: $e")
                    refreshStatusSummary()
                }

                override fun onRcDisconnect() {
                    rcConnected = false
                    c12Gimbal.setRcConnected(false)
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

    private fun logRcChannelSettings() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            KeyManager.get(RemoteControllerKey.KeyChannelSettings, object : CompletionCallbackWith<ChannelSettings> {
                override fun onSuccess(value: ChannelSettings?) {
                    Log.d(TAG, "RC_CHANNEL_SETTINGS $value")
                }

                override fun onFailure(e: SkyException) {
                    Log.w(TAG, "Unable to read RC channel settings: $e")
                }
            })
        }.onFailure { Log.w(TAG, "RC channel settings request failed", it) }
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
        val effectiveChannels = channels.copyOf().also { values ->
            if (BuildConfig.CONTROL_SIMULATOR) debugCh05Override?.let { values[4] = it }
        }
        val now = System.currentTimeMillis()
        c12Gimbal.updateChannels(effectiveChannels)
        if (BuildConfig.DEBUG && now - lastRcChannelsLogTimeMs >= RC_CHANNEL_LOG_INTERVAL_MS) {
            lastRcChannelsLogTimeMs = now
            Log.d(TAG, "RC_CHANNELS ${channels.joinToString(prefix = "[", postfix = "]")} override=$debugCh05Override")
        }
        val level = speedLevelFromCh12(channels[11])
        runOnUiThread {
            updateSpeedBubbleUi(level)
            // In production, Jetson SBUS telemetry is the authoritative CH05
            // source. The G30 RC SDK uses a different raw scale (for example
            // 1950 instead of 1722 for UP); mixing both sources made the raw
            // diagnostic flicker even though the physical mode was stable.
            if (BuildConfig.CONTROL_SIMULATOR) {
                renderOperatorState(operatorControl.updateChannels(effectiveChannels))
            }
            if (!BuildConfig.CONTROL_SIMULATOR && operatorSnapshot.ch05 == Ch05Position.DOWN_INTERNET &&
                operatorSnapshot.internetArmed) {
                udpSender.send(ControlProtocol.rcChannels(++commandSequence, effectiveChannels))
            }
        }
    }

    private fun renderOperatorState(snapshot: OperatorSnapshot) {
        operatorSnapshot = snapshot
        val host = if (snapshot.route == ConnectionRoute.LOCAL) localJetsonHost()
        else internetJetsonHost()
        if (host.isNotBlank()) udpSender.setTarget(host, DEFAULT_UDP_PORT)

        val source = when (snapshot.ch05) {
            Ch05Position.UP_RF_SIDEX -> if (OperatorControl.isSidexActive(snapshot.sidexState)) {
                "SIDEX DEMO"
            } else "RF MANUAL"
            Ch05Position.MIDDLE_SAFE -> "SAFE / DISABLED"
            Ch05Position.DOWN_INTERNET -> "INTERNET CONTROL"
            Ch05Position.UNKNOWN -> "RC CHANNELS UNKNOWN"
        }
        // Present the operator-facing G30 channel convention while retaining
        // the authoritative 11-bit SBUS value internally for mode decisions.
        val ch05Diagnostic = OperatorControl.ch05DisplayValue(snapshot.ch05)
            ?.let { " · CH05 $it" }
            .orEmpty()
        val simulationLabel = if (BuildConfig.CONTROL_SIMULATOR) {
            if (debugCh05Override == null) "SIMULATOR · TAP FOR SIM CH05" else "SIM CH05 OVERRIDE · TAP TO CHANGE"
        } else "LIVE · ${snapshot.telemetrySource}"
        val runtimeDiagnostic = if (BuildConfig.CONTROL_SIMULATOR) "SIMULATED RUNTIME" else buildString {
            append("SENSORS ${snapshot.sensorState} - SIDEX ${snapshot.sidexState.name}")
            append(" - ${snapshot.sidexStage}")
            append(" - ${snapshot.sidexCourseProfile.replace('_', ' ')}")
            if (snapshot.sidexFaultCode.isNotBlank()) append(" - FAULT ${snapshot.sidexFaultCode}")
        }
        controlState.text = "$source$ch05Diagnostic\n$simulationLabel\n$runtimeDiagnostic"
        val runtimeHealthy = snapshot.sensorState == "READY" &&
            snapshot.telemetrySource != "STALE" && snapshot.telemetrySource != "ODOMETRY_FAULT"
        controlState.setTextColor(ContextCompat.getColor(this,
            if (BuildConfig.CONTROL_SIMULATOR || !runtimeHealthy) R.color.industrial_warning
            else R.color.industrial_success))

        val displaySpeed = snapshot.speedMetresPerSecond ?: if (BuildConfig.CONTROL_SIMULATOR) 0.0 else null
        val displayRoll = snapshot.rollDegrees ?: if (BuildConfig.CONTROL_SIMULATOR) 0.0 else null
        val displayPitch = snapshot.pitchDegrees ?: if (BuildConfig.CONTROL_SIMULATOR) 0.0 else null
        val speed = displaySpeed?.let { "%.2f m/s".format(it) } ?: "—"
        val displayYaw = snapshot.yawDegrees ?: if (BuildConfig.CONTROL_SIMULATOR) 0.0 else null
        telemetry.text = "SPEED $speed   ROLL ${ControlProtocol.direction(displayRoll, "LEFT", "RIGHT")}   " +
            "PITCH ${ControlProtocol.direction(displayPitch, "BACKWARD", "FORWARD")}   " +
            "YAW ${ControlProtocol.direction(displayYaw, "RIGHT", "LEFT")}"
        turnQueue.text = if (snapshot.mapRecording) {
            "MAPPING COURSE · %.0f s\nDRIVE ONE LAP, RETURN TO MARK".format(snapshot.mapDurationSeconds)
        } else if (snapshot.mapState == "SAVING") {
            "BUILDING 3D MAP\nDO NOT POWER OFF"
        } else if (snapshot.sidexAvailable || snapshot.sidexState != SidexState.STOPPED) {
            buildString {
                append(if (snapshot.sidexSimulatedGeometry) "SIDEX FLAT DRAFT" else "SIDEX RAMP LIVE")
                when (snapshot.sidexState) {
                    SidexState.STOPPED -> {
                        append(" · READY")
                        if (snapshot.sidexSimulatedGeometry) {
                            val sideA = snapshot.sidexRectangleSideAMetres
                            val sideB = snapshot.sidexRectangleSideBMetres
                            if (sideA != null && sideB != null) append(" · RECT %.2f × %.2f m".format(sideA, sideB))
                        }
                    }
                    SidexState.FAULT -> {
                        append(" · FAULT")
                        if (snapshot.sidexFaultCode.isNotBlank()) append(" · ${snapshot.sidexFaultCode}")
                    }
                    SidexState.COMPLETE -> append(" · ${snapshot.sidexStage.replace('_', ' ')}")
                    else -> {
                        append(" · LAP ${snapshot.sidexLap} · ${snapshot.sidexStage.replace('_', ' ')}")
                        snapshot.sidexStageDistanceMetres?.let { append(" · %.2f m".format(it)) }
                    }
                }
            }
        } else ""

        udpTargetButton.text = when (snapshot.ch05) {
            Ch05Position.UP_RF_SIDEX -> "LOCAL IP"
            Ch05Position.DOWN_INTERNET -> "TAILSCALE IP"
            Ch05Position.MIDDLE_SAFE, Ch05Position.UNKNOWN -> "DESTINATION IP"
        }
        udpTargetButton.visibility = if (
            snapshot.ch05 == Ch05Position.UP_RF_SIDEX || snapshot.ch05 == Ch05Position.DOWN_INTERNET
        ) View.VISIBLE else View.GONE
        udpTargetButton.isEnabled = udpTargetButton.visibility == View.VISIBLE
        internetArmButton.visibility = if (
            snapshot.ch05 == Ch05Position.DOWN_INTERNET
        ) View.VISIBLE else View.GONE
        internetArmButton.text = if (snapshot.internetArmed) "INTERNET ARMED" else "INTERNET SAFE"
        zeroTelemetryButton.isEnabled = snapshot.ch05 != Ch05Position.UNKNOWN &&
            runtimeHealthy &&
            kotlin.math.abs(snapshot.speedMetresPerSecond ?: Double.POSITIVE_INFINITY) <= 0.03 &&
            !OperatorControl.isSidexActive(snapshot.sidexState) &&
            !snapshot.teachRecording && !snapshot.mapRecording && snapshot.mapState != "SAVING" &&
            !snapshot.sidexArmed &&
            !(snapshot.ch05 == Ch05Position.DOWN_INTERNET && snapshot.internetArmed)
        sidexDemoButton.visibility = if (snapshot.ch05 == Ch05Position.UP_RF_SIDEX) View.VISIBLE else View.GONE
        sidexDemoButton.isEnabled = when (snapshot.sidexState) {
            SidexState.STARTING -> false
            SidexState.RUNNING, SidexState.PAUSED -> true
            SidexState.STOPPED, SidexState.COMPLETE, SidexState.FAULT ->
                snapshot.sidexAvailable && runtimeHealthy && !snapshot.teachRecording && !snapshot.mapRecording
        }
        sidexDemoButton.text = when (snapshot.sidexState) {
            SidexState.STARTING -> "STARTING SIDEX…"
            SidexState.RUNNING, SidexState.PAUSED -> "STOP SIDEX DEMO"
            SidexState.STOPPED, SidexState.COMPLETE, SidexState.FAULT ->
                if (snapshot.sidexAvailable) "START SIDEX DEMO" else "WAIT: ${snapshot.sidexWaitReason.ifBlank { "BACKEND" }}"
        }
        pendingCollisionMonitorState?.let { requested ->
            if (snapshot.collisionMonitorActive == requested) {
                pendingCollisionMonitorState = null
            } else if (System.currentTimeMillis() >= collisionCommandDeadlineMs) {
                pendingCollisionMonitorState = null
                setStatus("Collision monitor command was not confirmed by Jetson")
            }
        }
        collisionMonitorButton.visibility = if (
            snapshot.ch05 == Ch05Position.UP_RF_SIDEX && OperatorControl.isSidexActive(snapshot.sidexState)
        ) View.VISIBLE else View.GONE
        collisionMonitorButton.isEnabled = pendingCollisionMonitorState == null &&
            snapshot.telemetrySource != "STALE"
        collisionMonitorButton.text = when {
            pendingCollisionMonitorState != null -> "COLLISION…"
            snapshot.collisionMonitorActive -> "COLLISION ON"
            else -> "COLLISION OFF"
        }
        collisionMonitorButton.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (snapshot.collisionMonitorActive) R.color.industrial_success else R.color.industrial_danger
        )
        sidexMapButton.visibility = if (
            snapshot.ch05 == Ch05Position.UP_RF_SIDEX || snapshot.mapRecording || snapshot.mapState == "SAVING"
        ) View.VISIBLE else View.GONE
        sidexMapButton.isEnabled = snapshot.mapState != "SAVING" && (snapshot.mapRecording || (
            snapshot.mapAvailable && runtimeHealthy &&
                !OperatorControl.isSidexActive(snapshot.sidexState) && !snapshot.sidexArmed
        ))
        sidexMapButton.text = if (snapshot.mapRecording) {
            "SAVE MAP"
        } else if (snapshot.mapState == "SAVING") {
            "SAVING MAP…"
        } else if (snapshot.mapAvailable) {
            "MAP COURSE"
        } else {
            "MAP BACKEND WAIT"
        }

        val manualTurnsEnabled = (
            snapshot.ch05 == Ch05Position.UP_RF_SIDEX ||
                (snapshot.ch05 == Ch05Position.DOWN_INTERNET && snapshot.internetArmed)
            ) && !snapshot.neutralInterlock && !OperatorControl.isSidexActive(snapshot.sidexState)
        listOf<Button>(findViewById(R.id.btnL2), findViewById(R.id.btnL1), findViewById(R.id.btnR1), findViewById(R.id.btnR2))
            .forEach { it.isEnabled = manualTurnsEnabled }

        lastActiveTurn = snapshot.activeTurn
        setStatus(
            when {
                snapshot.obstaclePaused ->
                    "OBSTACLE DETECTED · TANKER STOPPED · REMOVE IT TO AUTO-RESUME (30 s limit)"
                snapshot.sidexState == SidexState.FAULT -> sidexFaultMessage(snapshot)
                else -> snapshot.message
            }
        )
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
                ViewMode.SIX -> "4 CAM"
                ViewMode.FULLSCREEN -> "FULL"
            }
            status.text = "$rc   |   CAMERAS ${videoGrid.liveCount()}/$CAMERA_COUNT LIVE   |   $view   |   ${udpSender.targetText()}"
        }
    }

    private fun setStatus(text: String) {
        if (::status.isInitialized) runOnUiThread { status.text = text }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        if (::c12Gimbal.isInitialized) c12Gimbal.start()
    }

    override fun onStop() {
        if (::c12Gimbal.isInitialized) c12Gimbal.stop()
        super.onStop()
    }

    override fun onDestroy() {
        rcPollHandler.removeCallbacks(operatorTickRunnable)
        stopSpeedChannelPolling()
        if (::c12Gimbal.isInitialized) c12Gimbal.stop()
        runCatching { RCSDKManager.disconnectRC() }
        if (::videoGrid.isInitialized) videoGrid.stopAll(clearUrls = false)
        telemetryReceiver.close()
        udpSender.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TankerMainActivity"
        private const val DEFAULT_JETSON_IP = "192.168.144.20"
        private const val DEFAULT_TAILSCALE_IP = "100.110.108.96"
        private const val DEFAULT_UDP_PORT = 5005
        private const val TELEMETRY_UDP_PORT = 5006
        private const val CAMERA_COUNT = 4
        private val CAMERA_NAMES = listOf("FRONT C12", "FRONT MIDDLE", "BACK LEFT", "BACK RIGHT")

        private const val PREFERENCES_NAME = "tanker_operator_settings"
        private const val PREF_VIEW_MODE = "camera_view_mode"
        private const val PREF_FOCUSED_CAMERA = "focused_camera"
        private const val PREF_LOCAL_HOST = "local_jetson_host"
        private const val PREF_INTERNET_HOST = "internet_jetson_host"
        private const val PREF_CAMERA_SCHEMA = "camera_config_schema"
        private const val CAMERA_SCHEMA_VERSION = 3
        private fun cameraUrlKey(slot: Int) = "camera_url_$slot"

        private const val CH_MIN = 1050
        private const val CH_MAX = 1950
        private const val SPEED_LEVEL_COUNT = 7
        private const val RC_CHANNEL_POLL_INTERVAL_MS = 100L
        private const val RC_FAILURE_LOG_INTERVAL_MS = 2000L
        private const val RC_CHANNEL_LOG_INTERVAL_MS = 500L
        private const val OPERATOR_TICK_MS = 100L
    }
}
