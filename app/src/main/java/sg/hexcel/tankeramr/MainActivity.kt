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
import sg.hexcel.tankeramr.control.AutonomousState
import sg.hexcel.tankeramr.control.Ch05Position
import sg.hexcel.tankeramr.control.ConnectionRoute
import sg.hexcel.tankeramr.control.ControlProtocol
import sg.hexcel.tankeramr.control.OperatorControl
import sg.hexcel.tankeramr.control.OperatorSnapshot
import sg.hexcel.tankeramr.control.UdpCommandSender
import sg.hexcel.tankeramr.control.UdpTelemetryReceiver
import sg.hexcel.tankeramr.network.CameraScanner
import sg.hexcel.tankeramr.video.VideoGridController

class MainActivity : AppCompatActivity() {
    private enum class ViewMode { FOCUS, FOUR, SIX, FULLSCREEN }
    private enum class C12Mode { VISIBLE, THERMAL }
    private enum class PendingAutonomousAction { START, STOP }

    private lateinit var status: TextView
    private lateinit var cameraWall: ConstraintLayout
    private lateinit var cameraFrames: List<FrameLayout>
    private lateinit var cameraGestureLayers: List<View>
    private lateinit var viewModeButton: Button
    private lateinit var c12ModeButton: Button
    private lateinit var videoButton: Button
    private lateinit var udpTargetButton: Button
    private lateinit var controlState: TextView
    private lateinit var telemetry: TextView
    private lateinit var turnQueue: TextView
    private lateinit var internetArmButton: Button
    private lateinit var autonomousProcessButton: Button
    private lateinit var autonomousArmButton: Button
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
    private val operatorControl = OperatorControl()
    private var operatorSnapshot = OperatorSnapshot()
    private var lastActiveTurn: TurnButton? = null
    private var commandSequence = 0L
    private var debugCh05Override: Int? = null
    private var pendingAutonomousAction: PendingAutonomousAction? = null
    private var c12FallbackAttempted = false
    private var c12OfflineNotified = false

    private val operatorTickRunnable = object : Runnable {
        override fun run() {
            val snapshot = operatorControl.tick()
            renderOperatorState(snapshot)
            if (BuildConfig.CONTROL_SIMULATOR) handleAutonomousCompletion(snapshot)
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
        videoButton = findViewById(R.id.btnStopVideo)
        udpTargetButton = findViewById(R.id.btnUdpTarget)
        controlState = findViewById(R.id.tvControlState)
        telemetry = findViewById(R.id.tvTelemetry)
        turnQueue = findViewById(R.id.tvTurnQueue)
        internetArmButton = findViewById(R.id.btnInternetArm)
        autonomousProcessButton = findViewById(R.id.btnAutonomousProcess)
        autonomousArmButton = findViewById(R.id.btnAutonomousArm)

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

        restoreOperatorState()
        videoGrid.setUrls(loadCameraUrls())
        bindViewModeButton()
        bindVideoButtons()
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
                handleAutonomousCompletion(snapshot)
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
            ?.takeUnless { it == ViewMode.FULLSCREEN }
            ?: ViewMode.FOCUS
        previousViewMode = currentViewMode
        // Deterministic operator startup: always begin with the visible C12 feed.
        // THERMAL remains available as a session toggle but is never restored after relaunch.
        c12Mode = C12Mode.VISIBLE
        migrateCameraConfiguration()
    }

    private fun migrateCameraConfiguration() {
        if (preferences.getInt(PREF_CAMERA_SCHEMA, 0) >= CAMERA_SCHEMA_VERSION) return
        val defaults = CameraScanner.defaultSixCameraUrls()
        val existing = (1..4).map { preferences.getString(cameraUrlKey(it), null)?.trim().orEmpty() }
        val knownAssignments = existing.map { url ->
            CameraScanner.VERIFIED_IP_CAMERA_IPS.firstOrNull { ip -> url.contains(ip) }
        }
        val onlyKnownOrBlank = existing.zip(knownAssignments).all { (url, ip) -> url.isBlank() || ip != null }
        val knownLayoutIsInvalid = knownAssignments.any { it == null } ||
            knownAssignments.filterNotNull().distinct().size != CameraScanner.VERIFIED_IP_CAMERA_IPS.size
        preferences.edit().apply {
            // Repair duplicate/missing assignments only when all saved entries belong to
            // the known TankerAMR camera set. Unrelated custom RTSP URLs are preserved.
            for (slot in 1..4) {
                val key = cameraUrlKey(slot)
                val current = existing[slot - 1]
                val migrated = if (onlyKnownOrBlank && knownLayoutIsInvalid) defaults[slot]
                else if (current.isBlank()) defaults[slot]
                else CameraScanner.preferMainStream(current)
                putString(key, migrated)
            }
            putInt(PREF_CAMERA_SCHEMA, CAMERA_SCHEMA_VERSION)
        }.apply()
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
            .setNeutralButton("Restore verified") { _, _ -> restoreVerifiedCameraLayout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreVerifiedCameraLayout() {
        val urls = CameraScanner.defaultSixCameraUrls().toMutableList().also { it[0] = c12Url() }
        saveCameraUrls(urls)
        preferences.edit().putInt(PREF_CAMERA_SCHEMA, CAMERA_SCHEMA_VERSION).apply()
        videoGrid.setUrls(urls)
        if (!videoStoppedByOperator) videoGrid.playVisible(visibleSlots(currentViewMode))
        setStatus("Restored C12 and four verified 1080p IP cameras")
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
            AlertDialog.Builder(this)
                .setTitle("Manual connection route")
                .setItems(arrayOf(
                    "LOCAL · ${localJetsonHost()}",
                    "INTERNET · Tailscale VPN",
                    "Configure LOCAL address",
                    "Configure INTERNET address",
                    "Test current connection",
                    "Safety: route changes require CH05 up"
                )) { _, which ->
                    when (which) {
                        0 -> selectOperatorRoute(ConnectionRoute.LOCAL)
                        1 -> {
                            val host = preferences.getString(PREF_INTERNET_HOST, "").orEmpty()
                            if (host.isBlank()) showInternetEndpointEditor()
                            else selectOperatorRoute(ConnectionRoute.INTERNET)
                        }
                        2 -> showLocalEndpointEditor()
                        3 -> showInternetEndpointEditor()
                        4 -> sendProductionCommand("ping")
                        else -> setStatus("Route change applies a 0.5 s neutral interlock")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun localJetsonHost(): String =
        preferences.getString(PREF_LOCAL_HOST, DEFAULT_JETSON_IP)?.trim().orEmpty()
            .ifBlank { DEFAULT_JETSON_IP }

    private fun showLocalEndpointEditor() {
        val input = EditText(this).apply {
            hint = "Jetson Ethernet IP"
            setSingleLine(true)
            setText(localJetsonHost())
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("LOCAL Jetson endpoint")
            .setMessage("Default: $DEFAULT_JETSON_IP. Commands use UDP port $DEFAULT_UDP_PORT.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isBlank()) setStatus("LOCAL endpoint unchanged: address is empty")
                else {
                    preferences.edit().putString(PREF_LOCAL_HOST, host).apply()
                    if (operatorSnapshot.route == ConnectionRoute.LOCAL) udpSender.setTarget(host, DEFAULT_UDP_PORT)
                    setStatus("LOCAL endpoint saved: $host:$DEFAULT_UDP_PORT")
                }
            }
            .setNeutralButton("Restore default") { _, _ ->
                preferences.edit().putString(PREF_LOCAL_HOST, DEFAULT_JETSON_IP).apply()
                udpSender.setTarget(DEFAULT_JETSON_IP, DEFAULT_UDP_PORT)
                setStatus("LOCAL endpoint restored: $DEFAULT_JETSON_IP:$DEFAULT_UDP_PORT")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInternetEndpointEditor() {
        val input = EditText(this).apply {
            hint = "Jetson Tailscale IP (for example 100.x.x.x)"
            setSingleLine(true)
            setText(preferences.getString(PREF_INTERNET_HOST, ""))
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("INTERNET Jetson endpoint")
            .setMessage("Enter the Jetson's exact Tailscale IP. It is intentionally not guessed.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isBlank()) setStatus("INTERNET endpoint not saved: address is empty")
                else {
                    preferences.edit().putString(PREF_INTERNET_HOST, host).apply()
                    setStatus("INTERNET endpoint saved: $host:$DEFAULT_UDP_PORT")
                    selectOperatorRoute(ConnectionRoute.INTERNET)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindOperatorControls() {
        internetArmButton.setOnClickListener {
            val result = operatorControl.toggleInternetArm()
            renderOperatorState(result)
            if (result.message == "INTERNET ARMED" || result.message == "INTERNET DISARMED") {
                sendProductionCommand("internet_arm", JSONObject().put("armed", result.internetArmed))
            }
        }
        autonomousProcessButton.setOnClickListener {
            if (BuildConfig.CONTROL_SIMULATOR) {
                val before = operatorControl.snapshot().autonomousState
                val result = operatorControl.toggleAutonomousProcess()
                renderOperatorState(result)
                if (result.autonomousState != before) pendingAutonomousAction =
                    if (result.autonomousState == AutonomousState.STARTING) PendingAutonomousAction.START
                    else PendingAutonomousAction.STOP
            } else when (operatorSnapshot.autonomousState) {
                AutonomousState.STOPPED, AutonomousState.FAULT -> confirmStartAutonomous()
                AutonomousState.READY -> requestAutonomousProcess(PendingAutonomousAction.STOP)
                AutonomousState.STARTING -> setStatus("AUTONOMOUS startup is already in progress")
            }
        }
        autonomousArmButton.setOnClickListener {
            val result = operatorControl.toggleAutonomousArm()
            renderOperatorState(result)
            if (result.message == "AUTONOMOUS ARMED" || result.message == "AUTONOMOUS DISARMED") {
                sendProductionCommand("autonomous_arm", JSONObject().put("armed", result.autonomousArmed))
            }
        }
    }

    private fun bindDebugSimulatorControls() {
        if (!BuildConfig.CONTROL_SIMULATOR) return
        controlState.setOnClickListener {
            val choices = arrayOf(
                "UP · MANUAL",
                "MIDDLE · SAFE",
                "DOWN · AUTONOMOUS",
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

    private fun selectOperatorRoute(route: ConnectionRoute) {
        val before = operatorControl.snapshot().route
        val result = operatorControl.selectRoute(route)
        if (result.route != before) sendRouteCommand(before, route)
        renderOperatorState(result)
    }

    private fun sendRouteCommand(previousRoute: ConnectionRoute, route: ConnectionRoute) {
        if (BuildConfig.CONTROL_SIMULATOR) return
        val packet = ControlProtocol.command(
            ++commandSequence,
            "connection_route",
            JSONObject().put("route", route.name)
        )
        val previousHost = if (previousRoute == ConnectionRoute.LOCAL) localJetsonHost()
        else preferences.getString(PREF_INTERNET_HOST, "").orEmpty()
        if (previousHost.isNotBlank()) udpSender.sendTo(previousHost, DEFAULT_UDP_PORT, packet)

        // Returning to LOCAL must remain recoverable even when the selected
        // INTERNET/Tailscale path is currently unavailable.
        if (route == ConnectionRoute.LOCAL && previousHost != localJetsonHost()) {
            udpSender.sendTo(localJetsonHost(), DEFAULT_UDP_PORT, packet)
        }
    }

    private fun confirmStartAutonomous() {
        AlertDialog.Builder(this)
            .setTitle("Start AUTONOMOUS?")
            .setMessage(
                "This starts Nav2 and Collision Monitor. It does not arm motion. " +
                    "Movement still requires CH05 down and a separate AUTONOMOUS ARMED action."
            )
            .setPositiveButton("Start") { _, _ -> requestAutonomousProcess(PendingAutonomousAction.START) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestAutonomousProcess(action: PendingAutonomousAction) {
        pendingAutonomousAction = action
        sendProductionCommand(
            "autonomous_process",
            JSONObject().put("action", if (action == PendingAutonomousAction.START) "start" else "stop")
        )
        setStatus(if (action == PendingAutonomousAction.START) "Starting AUTONOMOUS; waiting for READY"
            else "Stopping AUTONOMOUS; waiting for full shutdown")
    }

    private fun handleAutonomousCompletion(snapshot: OperatorSnapshot) {
        when (pendingAutonomousAction) {
            PendingAutonomousAction.START -> when (snapshot.autonomousState) {
                AutonomousState.READY -> {
                    pendingAutonomousAction = null
                    AlertDialog.Builder(this)
                        .setTitle("AUTONOMOUS started")
                        .setMessage(
                            "Nav2 and Collision Monitor are READY. Motion remains disabled until " +
                                "CH05 is down and AUTONOMOUS is explicitly armed."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
                AutonomousState.FAULT -> {
                    pendingAutonomousAction = null
                    AlertDialog.Builder(this).setTitle("AUTONOMOUS start failed")
                        .setMessage(snapshot.message).setPositiveButton("OK", null).show()
                }
                else -> Unit
            }
            PendingAutonomousAction.STOP -> if (snapshot.autonomousState == AutonomousState.STOPPED) {
                pendingAutonomousAction = null
                AlertDialog.Builder(this)
                    .setTitle("AUTONOMOUS stopped")
                    .setMessage(
                        "Nav2 and Collision Monitor have stopped fully. Livox, FAST-LIO, speed, " +
                            "roll and pitch telemetry remain active."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
            null -> Unit
        }
    }

    private fun sendProductionCommand(type: String, payload: JSONObject = JSONObject()) {
        if (!BuildConfig.CONTROL_SIMULATOR) udpSender.send(ControlProtocol.command(++commandSequence, type, payload))
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
                    logRcChannelSettings()
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
        if (BuildConfig.DEBUG && now - lastRcChannelsLogTimeMs >= RC_CHANNEL_LOG_INTERVAL_MS) {
            lastRcChannelsLogTimeMs = now
            Log.d(TAG, "RC_CHANNELS ${channels.joinToString(prefix = "[", postfix = "]")} override=$debugCh05Override")
        }
        val level = speedLevelFromCh12(channels[11])
        runOnUiThread {
            updateSpeedBubbleUi(level)
            if (BuildConfig.CONTROL_SIMULATOR) {
                renderOperatorState(operatorControl.updateChannels(effectiveChannels))
            }
            if (!BuildConfig.CONTROL_SIMULATOR && operatorSnapshot.ch05 == Ch05Position.UP_MANUAL &&
                operatorSnapshot.route == ConnectionRoute.INTERNET && operatorSnapshot.internetArmed) {
                udpSender.send(ControlProtocol.rcChannels(++commandSequence, effectiveChannels))
            }
        }
    }

    private fun renderOperatorState(snapshot: OperatorSnapshot) {
        operatorSnapshot = snapshot
        val host = if (snapshot.route == ConnectionRoute.LOCAL) localJetsonHost()
        else preferences.getString(PREF_INTERNET_HOST, "").orEmpty()
        if (host.isNotBlank()) udpSender.setTarget(host, DEFAULT_UDP_PORT)

        val source = when (snapshot.ch05) {
            Ch05Position.UP_MANUAL -> "MANUAL ${snapshot.route.name}"
            Ch05Position.MIDDLE_SAFE -> "SAFE / DISABLED"
            Ch05Position.DOWN_AUTONOMOUS -> "AUTONOMOUS LOCAL"
            Ch05Position.UNKNOWN -> "RC CHANNELS UNKNOWN"
        }
        val ch05Diagnostic = snapshot.ch05Raw?.let { " · CH05 $it" }.orEmpty()
        val simulationLabel = if (BuildConfig.CONTROL_SIMULATOR) {
            if (debugCh05Override == null) "SIMULATOR · TAP FOR SIM CH05" else "SIM CH05 OVERRIDE · TAP TO CHANGE"
        } else "LIVE · ${snapshot.telemetrySource}"
        controlState.text = "$source$ch05Diagnostic\n$simulationLabel"
        controlState.setTextColor(ContextCompat.getColor(this,
            if (BuildConfig.CONTROL_SIMULATOR) R.color.industrial_warning else R.color.industrial_success))

        val displaySpeed = snapshot.speedMetresPerSecond ?: if (BuildConfig.CONTROL_SIMULATOR) 0.0 else null
        val displayRoll = snapshot.rollDegrees ?: if (BuildConfig.CONTROL_SIMULATOR) 0.0 else null
        val displayPitch = snapshot.pitchDegrees ?: if (BuildConfig.CONTROL_SIMULATOR) 0.0 else null
        val speed = displaySpeed?.let { "%.2f m/s".format(it) } ?: "—"
        telemetry.text = "SPEED $speed   ROLL ${ControlProtocol.direction(displayRoll, "LEFT", "RIGHT")}   " +
            "PITCH ${ControlProtocol.direction(displayPitch, "BACKWARD", "FORWARD")}"
        turnQueue.text = "TURN PENDING ${snapshot.totalTurns} / 6"

        udpTargetButton.text = snapshot.route.name
        udpTargetButton.isEnabled = snapshot.ch05 == Ch05Position.UP_MANUAL
        internetArmButton.visibility = if (snapshot.ch05 == Ch05Position.UP_MANUAL && snapshot.route == ConnectionRoute.INTERNET) View.VISIBLE else View.GONE
        internetArmButton.text = if (snapshot.internetArmed) "INTERNET ARMED" else "INTERNET SAFE"
        autonomousProcessButton.visibility = if (
            snapshot.ch05 == Ch05Position.MIDDLE_SAFE || snapshot.ch05 == Ch05Position.DOWN_AUTONOMOUS
        ) View.VISIBLE else View.GONE
        autonomousProcessButton.isEnabled = snapshot.autonomousState != AutonomousState.STARTING
        autonomousProcessButton.text = when (snapshot.autonomousState) {
            AutonomousState.STOPPED, AutonomousState.FAULT -> "START AUTONOMOUS"
            AutonomousState.STARTING -> "STARTING…"
            AutonomousState.READY -> "STOP AUTONOMOUS"
        }
        autonomousArmButton.visibility = if (snapshot.ch05 == Ch05Position.DOWN_AUTONOMOUS) View.VISIBLE else View.GONE
        autonomousArmButton.isEnabled = snapshot.autonomousState == AutonomousState.READY
        autonomousArmButton.text = if (snapshot.autonomousArmed) "AUTONOMOUS ARMED" else "AUTONOMOUS SAFE"

        val manualTurnsEnabled = snapshot.ch05 == Ch05Position.UP_MANUAL && !snapshot.neutralInterlock &&
            (snapshot.route == ConnectionRoute.LOCAL || snapshot.internetArmed)
        listOf<Button>(findViewById(R.id.btnL2), findViewById(R.id.btnL1), findViewById(R.id.btnR1), findViewById(R.id.btnR2))
            .forEach { it.isEnabled = manualTurnsEnabled }

        lastActiveTurn = snapshot.activeTurn
        setStatus(snapshot.message)
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
        rcPollHandler.removeCallbacks(operatorTickRunnable)
        stopSpeedChannelPolling()
        runCatching { RCSDKManager.disconnectRC() }
        if (::videoGrid.isInitialized) videoGrid.stopAll(clearUrls = false)
        telemetryReceiver.close()
        udpSender.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TankerMainActivity"
        private const val DEFAULT_JETSON_IP = "192.168.144.20"
        private const val DEFAULT_UDP_PORT = 5005
        private const val TELEMETRY_UDP_PORT = 5006
        private const val CAMERA_COUNT = 6
        private val CAMERA_NAMES = listOf("FRONT C12", "CAMERA 2", "CAMERA 3", "CAMERA 4", "CAMERA 5", "CAMERA 6")

        private const val PREFERENCES_NAME = "tanker_operator_settings"
        private const val PREF_VIEW_MODE = "camera_view_mode"
        private const val PREF_FOCUSED_CAMERA = "focused_camera"
        private const val PREF_LOCAL_HOST = "local_jetson_host"
        private const val PREF_INTERNET_HOST = "internet_jetson_host"
        private const val PREF_CAMERA_SCHEMA = "camera_config_schema"
        private const val CAMERA_SCHEMA_VERSION = 2
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
