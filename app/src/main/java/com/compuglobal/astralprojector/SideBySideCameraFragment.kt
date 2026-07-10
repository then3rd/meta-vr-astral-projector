package com.compuglobal.astralprojector

import android.content.Context
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.MultiCameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows up to two UVC cameras side-by-side.
 *
 * AUSBC's [MultiCameraFragment] drives the USB device lifecycle: attach -> (auto) request
 * permission -> connect. We map each connected camera to one of two fixed on-screen slots
 * (left / right) and open its preview onto that slot's [AspectRatioTextureView].
 *
 * Both physical cameras report the same VID:PID (0c45:6366), so we can't tell which unit is which —
 * cameras fill slots in connection order. Which feed lands left vs right depends on which hub port
 * each camera is plugged into; fix the cabling if a stable left/right matters.
 *
 * Note: libausbc 3.2.7's CameraRequest exposes no preview-format setter — the UVC strategy
 * negotiates MJPEG by default, which is what we need for two streams to fit on one USB 2.0 bus.
 * If two cameras won't co-exist at 720p, lower [PREVIEW_WIDTH]/[PREVIEW_HEIGHT].
 */
class SideBySideCameraFragment : MultiCameraFragment(), ICameraStateCallBack {

    /**
     * How the (stretched-to-fill by AUSBC) frame is rescaled inside its pane via
     * [android.view.TextureView.setTransform]. Scale factors are relative to the
     * stretch-fill baseline, so STRETCH is the identity.
     */
    private enum class AspectMode(val labelRes: Int) {
        STRETCH(R.string.aspect_stretch),
        FIT_WIDTH(R.string.aspect_fit_width),
        FIT_HEIGHT(R.string.aspect_fit_height),
        FULL_FRAME(R.string.aspect_full_frame),
    }

    /** Slot 0 = left, slot 1 = right. Null means the slot is free. */
    private val slots = arrayOfNulls<MultiCameraClient.Camera>(SLOT_COUNT)

    /** Consecutive open-retry attempts per slot; reset to 0 on successful OPENED. */
    private val reopenAttempts = IntArray(SLOT_COUNT)

    /**
     * Slots whose closeCamera() was called intentionally by a swap (not a real detach).
     * onCameraDisConnected skips clearing the slot entry for these so the camera object
     * stays in the slots array and onCameraState(OPENED) can find it via slotOf().
     */
    private val swappingSlots = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Negotiated preview size per slot (may differ from the requested one, e.g. 640x480). */
    private val videoSizes = arrayOfNulls<PreviewSize>(SLOT_COUNT)

    private var aspectMode = AspectMode.STRETCH
    private var aspectToggle: TextView? = null

    // Video orientation, user-controlled (persisted). rotationDeg snaps to 0/90/180/270 and
    // flipH mirrors horizontally. Together they cover all 8 orientations, so the correct one
    // can always be dialed in on-device without recompiling.
    private var rotationDeg = 0
    private var flipH = false
    private var rotationToggle: TextView? = null
    private var flipToggle: TextView? = null

    // Which display pane (texture/status view) each logical connection slot renders into.
    // Left/right depends on hub port, not on which physical camera unit connected, so this
    // lets the user correct it on-screen. Swapping redirects the already-open camera's render
    // target to the other (already-attached) TextureView rather than moving views in the layout
    // — reparenting a TextureView detaches/destroys its SurfaceTexture and the camera never
    // rebinds to the new one, leaving the pane blank until the app restarts.
    private var swapped = false
    private fun displayIndexFor(logicalIdx: Int): Int = if (swapped) SLOT_COUNT - 1 - logicalIdx else logicalIdx

    private lateinit var textures: Array<AspectRatioTextureView>
    private lateinit var statuses: Array<TextView>

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dumps = 0

    // Debounce timestamp for thumbstick-driven focus movement in the settings menu.
    private var lastStickMove = 0L

    // Cached application context for resource lookups from lifecycle-independent callbacks.
    private var appCtx: Context? = null

    /** Resolve a string resource via the application context — never throws if detached. */
    private fun str(resId: Int, vararg args: Any): String {
        val c = appCtx ?: context?.applicationContext ?: return ""
        return if (args.isEmpty()) c.getString(resId) else c.getString(resId, *args)
    }

    // On-screen log overlay (retrievable without ADB or a card reader).
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var logOverlay: View? = null
    private val logLines = ArrayDeque<String>()

    // Settings controls row (toggled by the gear button; hidden by default).
    private var settingsList: ViewGroup? = null
    private var settingsScroll: View? = null
    private var settingsScrim: View? = null
    private var settingsToggle: TextView? = null

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        FileLogger.log("getRootView")
        // Cache the application context so status-string lookups from AUSBC camera callbacks don't
        // crash with "Fragment not attached" when the panel is detached (e.g. immersive app
        // backgrounded) while cameras keep streaming and firing state callbacks.
        appCtx = inflater.context.applicationContext
        val root = inflater.inflate(R.layout.fragment_side_by_side, container, false)
        textures = arrayOf(root.findViewById(R.id.textureLeft), root.findViewById(R.id.textureRight))
        statuses = arrayOf(root.findViewById(R.id.statusLeft), root.findViewById(R.id.statusRight))

        logScroll = root.findViewById(R.id.logScroll)
        logText = root.findViewById(R.id.logText)
        logOverlay = root.findViewById(R.id.logOverlay)
        val logToggle = root.findViewById<TextView>(R.id.logToggle)
        val logClose = root.findViewById<TextView>(R.id.logClose)
        val setLogVisible = { show: Boolean ->
            logOverlay?.visibility = if (show) View.VISIBLE else View.GONE
            logToggle.text = str(if (show) R.string.log_hide else R.string.log_show)
        }
        logToggle.setOnClickListener {
            setLogVisible(logOverlay?.visibility != View.VISIBLE)
        }
        logClose.setOnClickListener { setLogVisible(false) }

        // Gear button toggles the (transparent) controls row, which is hidden by default.
        settingsList = root.findViewById(R.id.settingsList)
        settingsScroll = root.findViewById(R.id.settingsScroll)
        settingsScrim = root.findViewById(R.id.settingsScrim)
        settingsScrim?.setOnClickListener { setSettingsVisible(false) }
        val settingsBtn = root.findViewById<TextView>(R.id.settingsToggle)
        settingsToggle = settingsBtn
        settingsBtn.setOnClickListener { toggleSettings() }

        root.findViewById<TextView>(R.id.buildTimestamp).text =
            str(R.string.build_time_label,
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(BuildConfig.BUILD_TIME)))

        aspectMode = loadAspectMode()
        val aspectBtn = root.findViewById<TextView>(R.id.aspectToggle)
        aspectToggle = aspectBtn
        aspectBtn.text = str(aspectMode.labelRes)
        aspectBtn.setOnClickListener {
            aspectMode = AspectMode.values()[(aspectMode.ordinal + 1) % AspectMode.values().size]
            FileLogger.log("aspect mode -> $aspectMode")
            saveAspectMode(aspectMode)
            aspectBtn.text = str(aspectMode.labelRes)
            applyAspectToAll()
        }

        swapped = loadSwapped()
        root.findViewById<TextView>(R.id.swapToggle).setOnClickListener {
            performSwap()
        }

        // Rotation: cycles 0 -> 90 -> 180 -> 270 (snaps to the four orientations).
        rotationDeg = loadRotation()
        val rotationBtn = root.findViewById<TextView>(R.id.rotationToggle)
        rotationToggle = rotationBtn
        rotationBtn.text = str(R.string.rotation_label, rotationDeg)
        rotationBtn.setOnClickListener {
            rotationDeg = (rotationDeg + 90) % 360
            FileLogger.log("rotation -> $rotationDeg")
            saveRotation(rotationDeg)
            rotationBtn.text = str(R.string.rotation_label, rotationDeg)
            applyAspectToAll()
        }

        // Horizontal flip: mirrors the video left/right. Combined with rotation this covers all
        // eight possible orientations, so the correct one can always be set on-device.
        flipH = loadFlipH()
        val flipBtn = root.findViewById<TextView>(R.id.flipToggle)
        flipToggle = flipBtn
        flipBtn.text = str(if (flipH) R.string.flip_h_on else R.string.flip_h_off)
        flipBtn.setOnClickListener {
            flipH = !flipH
            FileLogger.log("flipH -> $flipH")
            saveFlipH(flipH)
            flipBtn.text = str(if (flipH) R.string.flip_h_on else R.string.flip_h_off)
            applyAspectToAll()
        }

        // Head-follow toggle. The fragment lives inside MainActivity (the panel), so it can't
        // reach ImmersiveActivity directly — instead it flips the shared preference, which
        // ImmersiveActivity observes via a change listener and applies to the panel's Followable.
        val followBtn = root.findViewById<TextView>(R.id.headFollowToggle)
        val initialFollow = SpatialControls.isHeadFollowEnabled(requireContext())
        followBtn.text = str(if (initialFollow) R.string.head_follow_on else R.string.head_follow_off)
        followBtn.setOnClickListener {
            val next = !SpatialControls.isHeadFollowEnabled(requireContext())
            SpatialControls.setHeadFollowEnabled(requireContext(), next)
            followBtn.text = str(if (next) R.string.head_follow_on else R.string.head_follow_off)
            FileLogger.log("headFollow -> $next")
        }

        // Smoothing toggle. Like head-follow, it just persists a preference that ImmersiveActivity
        // observes and applies to the HeadFollowSystem's interpolation.
        val smoothBtn = root.findViewById<TextView>(R.id.smoothingToggle)
        val initialSmooth = SpatialControls.isSmoothingEnabled(requireContext())
        smoothBtn.text = str(if (initialSmooth) R.string.smoothing_on else R.string.smoothing_off)
        smoothBtn.setOnClickListener {
            val next = !SpatialControls.isSmoothingEnabled(requireContext())
            SpatialControls.setSmoothingEnabled(requireContext(), next)
            smoothBtn.text = str(if (next) R.string.smoothing_on else R.string.smoothing_off)
            FileLogger.log("smoothing -> $next")
        }

        // Passthrough toggle. Persists a preference that ImmersiveActivity observes and applies via
        // scene.enablePassthrough — turning the mixed-reality background on/off live.
        val passthroughBtn = root.findViewById<TextView>(R.id.passthroughToggle)
        val initialPassthrough = SpatialControls.isPassthroughEnabled(requireContext())
        passthroughBtn.text = str(if (initialPassthrough) R.string.passthrough_on else R.string.passthrough_off)
        passthroughBtn.setOnClickListener {
            val next = !SpatialControls.isPassthroughEnabled(requireContext())
            SpatialControls.setPassthroughEnabled(requireContext(), next)
            passthroughBtn.text = str(if (next) R.string.passthrough_on else R.string.passthrough_off)
            FileLogger.log("passthrough -> $next")
        }

        // Curve slider: 0..100% maps directly to curve amount 0.0..1.0 (flat -> cylinder).
        val curveLabel = root.findViewById<TextView>(R.id.curveLabel)
        val curveSlider = root.findViewById<SeekBar>(R.id.curveSlider)
        val initialCurvePct = (SpatialControls.getPanelCurve(requireContext()) * 100f).toInt()
        curveSlider.progress = initialCurvePct
        curveLabel.text = str(R.string.curve_label, initialCurvePct)
        curveSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                curveLabel.text = str(R.string.curve_label, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            // Curvature is applied via a panel mesh rebuild (reshape) — doing that on every
            // progress tick tears down the surface the pointer ray is hit-testing against
            // mid-drag, so only commit once the drag ends.
            override fun onStopTrackingTouch(sb: SeekBar) {
                SpatialControls.setPanelCurve(requireContext(), sb.progress / 100f)
                FileLogger.log("curve -> ${sb.progress}%")
            }
        })

        // Scale slider: 0..100% maps to panel scale MIN_PANEL_SCALE..MAX_PANEL_SCALE.
        val scaleLabel = root.findViewById<TextView>(R.id.scaleLabel)
        val scaleSlider = root.findViewById<SeekBar>(R.id.scaleSlider)
        val initialScale = SpatialControls.getPanelScale(requireContext())
        scaleSlider.progress = scaleToProgress(initialScale)
        scaleLabel.text = str(R.string.scale_label, (initialScale * 100f).toInt())
        scaleSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val scale = progressToScale(progress)
                scaleLabel.text = str(R.string.scale_label, (scale * 100f).toInt())
                if (fromUser) SpatialControls.setPanelScale(requireContext(), scale)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        // Pane size settles after first layout (and can change); recompute the transform then.
        // displayIndexFor is its own inverse, so it also maps a display index back to whichever
        // logical slot currently renders into it.
        textures.forEachIndexed { idx, tv ->
            tv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) applyAspect(displayIndexFor(idx))
            }
        }

        // Fallback for when the automatic USB permission dialog never becomes visible/interactable
        // (observed stuck on Meta Quest 2) — lets the user re-fire requestPermission on demand.
        root.findViewById<TextView>(R.id.permissionRetry).setOnClickListener {
            FileLogger.log("manual permission retry tapped")
            retryAllPendingPermissions()
        }
        // Stream every log line (including ones buffered before now) to the overlay.
        FileLogger.setListener { line -> appendLog(line) }
        return root
    }

    /** FileLogger invokes this from arbitrary threads; marshal to the UI thread. */
    private fun appendLog(line: String) {
        mainHandler.post {
            val tv = logText ?: return@post
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            tv.text = logLines.joinToString("\n")
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun initView() {
        super.initView()
        FileLogger.log("initView; autoRequestPermission=${isAutoRequestPermission()}")
        // Periodically dump observed device/permission state so a stuck flow is diagnosable offline.
        scheduleStateDump()
    }

    override fun onCameraAttached(camera: MultiCameraClient.Camera) {
        val d = camera.getUsbDevice()
        FileLogger.log("onCameraAttached ${desc(d)} hasPermission=${safeHasPermission(d)} mapSize=${getCameraMap().size}")
        val idx = firstFreeSlot()
        if (idx >= 0) setStatus(displayIndexFor(idx), str(R.string.status_connecting))
    }

    override fun onCameraDetached(camera: MultiCameraClient.Camera) {
        FileLogger.log("onCameraDetached ${desc(camera.getUsbDevice())}")
        val idx = slotOf(camera)
        if (idx >= 0) {
            slots[idx] = null
            videoSizes[idx] = null
            setStatus(displayIndexFor(idx), str(R.string.status_waiting))
        }
        runCatching { camera.closeCamera() }.onFailure { FileLogger.log("closeCamera(detach) failed", it) }
    }

    override fun onCameraConnected(camera: MultiCameraClient.Camera) {
        val d = camera.getUsbDevice()
        FileLogger.log("onCameraConnected ${desc(d)}")
        val idx = firstFreeSlot()
        if (idx < 0) {
            FileLogger.log("no free slot; ignoring extra camera ${desc(d)}")
            setStatus(SLOT_COUNT - 1, str(R.string.status_extra))
            runCatching { camera.closeCamera() }
            return
        }
        slots[idx] = camera
        camera.setCameraStateCallBack(this)
        val displayIdx = displayIndexFor(idx)
        try {
            FileLogger.log("openCamera slot=$idx display=$displayIdx ${PREVIEW_WIDTH}x$PREVIEW_HEIGHT")
            camera.openCamera(textures[displayIdx], buildRequest())
            setStatus(displayIdx, str(R.string.status_opening))
        } catch (t: Throwable) {
            FileLogger.log("openCamera slot=$idx FAILED", t)
            setStatus(displayIdx, str(R.string.status_error, t.message ?: "openCamera threw"))
        }
        requestPermissionForPendingCameras()
    }

    override fun onCameraDisConnected(camera: MultiCameraClient.Camera) {
        FileLogger.log("onCameraDisConnected ${desc(camera.getUsbDevice())}")
        val idx = slotOf(camera)
        if (idx >= 0 && !swappingSlots.contains(idx)) {
            // Real disconnection — clear the slot so the camera can reconnect into a free slot.
            slots[idx] = null
            videoSizes[idx] = null
            setStatus(displayIndexFor(idx), str(R.string.status_disconnected))
        }
        // If idx is in swappingSlots, closeCamera() was called by reopenOnDisplay; leave the
        // slot intact so onCameraState(OPENED) can find the camera via slotOf().
        runCatching { camera.closeCamera() }
    }

    override fun onCameraState(
        self: MultiCameraClient.Camera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        FileLogger.log("onCameraState code=$code msg=$msg dev=${desc(self.getUsbDevice())}")
        val idx = slotOf(self)
        if (idx < 0) return
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                reopenAttempts[idx] = 0
                hideStatus(displayIndexFor(idx))
                // getPreviewSize() holds the size UVC actually negotiated, which can differ
                // from the requested 1280x720 (640x480 observed on Quest 2).
                videoSizes[idx] = runCatching { self.getPreviewSize() }.getOrNull()
                FileLogger.log("slot=$idx negotiated previewSize=${videoSizes[idx]?.width}x${videoSizes[idx]?.height}")
                applyAspect(idx)
            }
            ICameraStateCallBack.State.CLOSED -> setStatus(displayIndexFor(idx), str(R.string.status_disconnected))
            ICameraStateCallBack.State.ERROR -> {
                setStatus(displayIndexFor(idx), str(R.string.status_error, msg ?: "unknown"))
                // On hub reset both cameras detach+reattach; the second openCamera often races
                // with the first camera's libuvc teardown and gets errno -99. Retry with backoff.
                if (msg?.contains("open") == true && reopenAttempts[idx] < MAX_REOPEN_ATTEMPTS) {
                    scheduleReopen(self, idx)
                }
            }
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun requestPermissionForPendingCameras() {
        getCameraMap().values.forEach { cam ->
            val device = cam.getUsbDevice()
            if (!safeHasPermission(device)) {
                FileLogger.log("requestPermission for ${desc(device)}")
                runCatching { requestPermission(device) }
                    .onFailure { FileLogger.log("requestPermission FAILED", it) }
                return
            }
        }
    }

    /** Manual fallback: re-fire requestPermission for every camera still lacking it, not just the first. */
    private fun retryAllPendingPermissions() {
        val pending = getCameraMap().values.filter { !safeHasPermission(it.getUsbDevice()) }
        FileLogger.log("retryAllPendingPermissions: ${pending.size} camera(s) pending")
        pending.forEach { cam ->
            val device = cam.getUsbDevice()
            FileLogger.log("requestPermission (manual retry) for ${desc(device)}")
            runCatching { requestPermission(device) }
                .onFailure { FileLogger.log("requestPermission (manual retry) FAILED", it) }
        }
    }

    private fun scheduleStateDump() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val devices = runCatching { getDeviceList() }.getOrNull()
                val sb = StringBuilder("state-dump #${dumps}: deviceList=${devices?.size ?: "null"} mapSize=${getCameraMap().size}")
                getCameraMap().values.forEach { cam ->
                    val d = cam.getUsbDevice()
                    sb.append("\n    - ${desc(d)} hasPermission=${safeHasPermission(d)} opened=${runCatching { cam.isCameraOpened() }.getOrNull()}")
                }
                FileLogger.log(sb.toString())
                if (++dumps < 12) mainHandler.postDelayed(this, 3000)
            }
        }, 2000)
    }

    private fun safeHasPermission(device: UsbDevice?): Boolean =
        runCatching { hasPermission(device) }.getOrDefault(false)

    private fun desc(d: UsbDevice?): String =
        if (d == null) "null" else "name=${d.deviceName} id=${d.deviceId} vid=${d.vendorId} pid=${d.productId}"

    private fun firstFreeSlot(): Int = slots.indexOfFirst { it == null }

    private fun slotOf(camera: MultiCameraClient.Camera): Int =
        slots.indexOfFirst { it?.getUsbDevice()?.deviceId == camera.getUsbDevice().deviceId }

    // --- aspect handling --------------------------------------------------------

    private fun applyAspectToAll() {
        for (idx in 0 until SLOT_COUNT) applyAspect(idx)
    }

    /**
     * AUSBC renders the frame stretched to fill the whole TextureView surface, so aspect
     * correction is a display-time transform: scale the composited texture around the pane
     * center, relative to that stretch-fill baseline.
     *
     * [logicalIdx] is the connection slot (matches [videoSizes]); it's mapped to the texture
     * currently displaying it via [displayIndexFor], since a swap can redirect that mapping.
     */
    private fun applyAspect(logicalIdx: Int) {
        if (!::textures.isInitialized) return
        val tv = textures[displayIndexFor(logicalIdx)]
        tv.post {
            val viewW = tv.width.toFloat()
            val viewH = tv.height.toFloat()
            val cx = viewW / 2f
            val cy = viewH / 2f
            val size = videoSizes[logicalIdx]
            if (viewW <= 0f || viewH <= 0f || size == null || size.width <= 0 || size.height <= 0) {
                // Size not negotiated yet: apply orientation only, at the stretch-fill baseline.
                val (a, b) = axisScale(AspectMode.STRETCH, rotationDeg, 1, 1, viewW, viewH)
                tv.setTransform(orientMatrix(a, b, cx, cy))
                return@post
            }
            val (a, b) = axisScale(aspectMode, rotationDeg, size.width, size.height, viewW, viewH)
            tv.setTransform(orientMatrix(a, b, cx, cy))
        }
    }

    /**
     * Builds the display-time transform: negate the x scale for a horizontal flip, then rotate
     * the (already aspect-scaled) content about the pane center. setScale followed by postRotate
     * yields Rotate·(FlipH·Scale), i.e. the video's width axis is scaled by [a], its height axis
     * by [b], mirrored if [flipH], then the whole thing spun to [rotationDeg].
     */
    private fun orientMatrix(a: Float, b: Float, cx: Float, cy: Float): Matrix =
        Matrix().apply {
            setScale(if (flipH) -a else a, b, cx, cy)
            if (rotationDeg != 0) postRotate(rotationDeg.toFloat(), cx, cy)
        }

    /**
     * Computes the per-axis scale (relative to AUSBC's stretch-to-fill baseline) that renders a
     * [vw]x[vh] video into a [viewW]x[viewH] pane under the given [mode] and [deg] rotation.
     *
     * Works by choosing the on-pane lengths of the video's own width/height axes (tw, th) — kept
     * in the video's aspect ratio for every mode except STRETCH — accounting for the fact that a
     * 90/270° rotation swaps which pane dimension each axis spans. Returns (tw/viewW, th/viewH),
     * which are exactly the setScale factors since the baseline stretches one video axis across
     * each full pane dimension.
     */
    private fun axisScale(
        mode: AspectMode, deg: Int, vw: Int, vh: Int, viewW: Float, viewH: Float
    ): Pair<Float, Float> {
        val vertical = deg == 90 || deg == 270
        val r = vw.toFloat() / vh          // video aspect ratio (w/h)
        val tw: Float
        val th: Float
        when (mode) {
            AspectMode.STRETCH -> {
                tw = if (vertical) viewH else viewW
                th = if (vertical) viewW else viewH
            }
            AspectMode.FIT_WIDTH -> if (!vertical) { tw = viewW; th = viewW / r }
                                    else { th = viewW; tw = r * viewW }
            AspectMode.FIT_HEIGHT -> if (!vertical) { th = viewH; tw = r * viewH }
                                     else { tw = viewH; th = viewH / r }
            AspectMode.FULL_FRAME -> {
                th = if (!vertical) minOf(viewH, viewW / r) else minOf(viewW, viewH / r)
                tw = r * th
            }
        }
        return Pair(tw / viewW, th / viewH)
    }

    /** Panel scale (fraction) -> SeekBar progress 0..100 across the allowed scale range. */
    private fun scaleToProgress(scale: Float): Int {
        val range = SpatialControls.MAX_PANEL_SCALE - SpatialControls.MIN_PANEL_SCALE
        return (((scale - SpatialControls.MIN_PANEL_SCALE) / range) * 100f).toInt().coerceIn(0, 100)
    }

    /** SeekBar progress 0..100 -> panel scale (fraction) across the allowed scale range. */
    private fun progressToScale(progress: Int): Float {
        val range = SpatialControls.MAX_PANEL_SCALE - SpatialControls.MIN_PANEL_SCALE
        return SpatialControls.MIN_PANEL_SCALE + (progress / 100f) * range
    }

    private fun loadAspectMode(): AspectMode {
        val name = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ASPECT_MODE, null)
        return AspectMode.values().firstOrNull { it.name == name } ?: AspectMode.FULL_FRAME
    }

    private fun saveAspectMode(mode: AspectMode) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_ASPECT_MODE, mode.name).apply()
    }

    private fun performSwap() {
        swapped = !swapped
        FileLogger.log("swap -> $swapped")
        saveSwapped(swapped)
        for (logicalIdx in 0 until SLOT_COUNT) {
            val camera = slots[logicalIdx] ?: continue
            reopenOnDisplay(camera, logicalIdx)
        }
    }

    /**
     * Schedules a retry of openCamera for a slot that reported an open failure.
     * Delay grows with each attempt so the first camera's libuvc context has time to settle.
     */
    private fun scheduleReopen(camera: MultiCameraClient.Camera, logicalIdx: Int) {
        val attempt = ++reopenAttempts[logicalIdx]
        val delay = OPEN_RETRY_BASE_MS * attempt
        FileLogger.log("scheduleReopen slot=$logicalIdx attempt=$attempt delay=${delay}ms")
        mainHandler.postDelayed({
            if (slots[logicalIdx] != camera) return@postDelayed  // slot reassigned; skip
            val displayIdx = displayIndexFor(logicalIdx)
            setStatus(displayIdx, str(R.string.status_connecting))
            runCatching { camera.openCamera(textures[displayIdx], buildRequest()) }
                .onFailure { FileLogger.log("reopen slot=$logicalIdx attempt=$attempt FAILED", it) }
        }, delay)
    }

    /** Redirects an already-open camera's render target to its (possibly new) display texture. */
    private fun reopenOnDisplay(camera: MultiCameraClient.Camera, logicalIdx: Int) {
        val displayIdx = displayIndexFor(logicalIdx)
        setStatus(displayIdx, str(R.string.status_connecting))
        // Mark slot as intentionally swapping so onCameraDisConnected doesn't null it out.
        // The slot must stay populated so onCameraState(OPENED) can find the camera via slotOf().
        swappingSlots.add(logicalIdx)
        runCatching { camera.closeCamera() }.onFailure { FileLogger.log("swap closeCamera failed", it) }
        // Give closeCamera's async teardown (its own HandlerThread) a moment to finish before
        // reopening the same USB control block on a fresh one.
        mainHandler.postDelayed({
            swappingSlots.remove(logicalIdx)
            try {
                FileLogger.log("swap reopen slot=$logicalIdx -> display=$displayIdx")
                camera.setCameraStateCallBack(this)
                camera.openCamera(textures[displayIdx], buildRequest())
                setStatus(displayIdx, str(R.string.status_opening))
            } catch (t: Throwable) {
                FileLogger.log("swap reopen slot=$logicalIdx FAILED", t)
                setStatus(displayIdx, str(R.string.status_error, t.message ?: "swap reopen threw"))
            }
        }, SWAP_REOPEN_DELAY_MS)
    }

    private fun loadSwapped(): Boolean =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_SWAPPED, false)

    private fun saveSwapped(value: Boolean) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_SWAPPED, value).apply()
    }

    private fun loadRotation(): Int {
        val deg = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_ROTATION, 0)
        return if (deg % 90 == 0) ((deg % 360) + 360) % 360 else 0
    }

    private fun saveRotation(value: Int) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(PREF_ROTATION, value).apply()
    }

    private fun loadFlipH(): Boolean =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_FLIP_H, false)

    private fun saveFlipH(value: Boolean) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_FLIP_H, value).apply()
    }

    private fun buildRequest(): CameraRequest =
        CameraRequest.Builder()
            .setPreviewWidth(PREVIEW_WIDTH)
            .setPreviewHeight(PREVIEW_HEIGHT)
            .create()

    private fun setStatus(idx: Int, text: String) {
        if (!::statuses.isInitialized) return
        statuses[idx].post {
            statuses[idx].visibility = View.VISIBLE
            statuses[idx].text = text
        }
    }

    private fun hideStatus(idx: Int) {
        if (!::statuses.isInitialized) return
        statuses[idx].post { statuses[idx].visibility = View.GONE }
    }

    override fun onDestroyView() {
        FileLogger.setListener(null)
        logText = null
        logScroll = null
        logOverlay = null
        aspectToggle = null
        rotationToggle = null
        flipToggle = null
        settingsList = null
        settingsScroll = null
        settingsScrim = null
        settingsToggle = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // --- settings toggle + controller input -------------------------------------
    //
    // A gear button above the video toggles the (highly transparent) controls row, which is hidden
    // by default. A Quest controller can also drive it: MENU/Y opens the controls and focuses them
    // (or hides them if already open), then left/right step across items, up/down nudge a focused
    // slider, and A/center clicks. Navigation keys are only intercepted while the controls hold
    // focus, so normal pointer/hand-ray use of the camera view is unaffected.

    fun isSettingsVisible(): Boolean = settingsScroll?.visibility == View.VISIBLE

    fun toggleSettings() = setSettingsVisible(!isSettingsVisible())

    private fun setSettingsVisible(show: Boolean) {
        val scroll = settingsScroll ?: return
        scroll.visibility = if (show) View.VISIBLE else View.GONE
        settingsScrim?.visibility = if (show) View.VISIBLE else View.GONE
        settingsToggle?.text = str(if (show) R.string.settings_close else R.string.settings_open)
        FileLogger.log("settings controls ${if (show) "shown" else "hidden"}")
        if (!show) view?.findFocus()?.clearFocus()
    }

    /** Ordered list of focusable controls in the row (buttons + sliders), left to right. */
    private fun barItems(): List<View> {
        val list = settingsList ?: return emptyList()
        val out = ArrayList<View>()
        collectFocusables(list, out)
        return out
    }

    private fun collectFocusables(v: View, out: MutableList<View>) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectFocusables(v.getChildAt(i), out)
        } else if (v.isFocusable && v.visibility == View.VISIBLE) {
            out.add(v)
        }
        // SeekBars are focusable ViewGroups-of-nothing; include them explicitly.
        if (v is SeekBar && v.isFocusable && v.visibility == View.VISIBLE && v !in out) out.add(v)
    }

    private fun barHasFocus(): Boolean {
        val focus = view?.findFocus() ?: return false
        return barItems().contains(focus)
    }

    fun handleControllerKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            if (isSettingsVisible()) {
                setSettingsVisible(false)
            } else {
                setSettingsVisible(true)
                focusFirstItem()
            }
            return true
        }
        if (!isSettingsVisible() || !barHasFocus()) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                setSettingsVisible(false); true
            }
            KeyEvent.KEYCODE_DPAD_UP -> moveFocusStep(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocusStep(+1)
            KeyEvent.KEYCODE_DPAD_LEFT -> nudgeSlider(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudgeSlider(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER ->
                view?.findFocus()?.performClick() ?: false
            else -> false
        }
    }

    /** Thumbstick / hat navigation, active only while the controls are open and hold focus. */
    fun handleControllerMotion(event: MotionEvent): Boolean {
        if (!isSettingsVisible() || !barHasFocus()) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val x = event.getAxisValue(MotionEvent.AXIS_X).let {
            if (it == 0f) event.getAxisValue(MotionEvent.AXIS_HAT_X) else it
        }
        val y = event.getAxisValue(MotionEvent.AXIS_Y).let {
            if (it == 0f) event.getAxisValue(MotionEvent.AXIS_HAT_Y) else it
        }
        val now = System.currentTimeMillis()
        if (now - lastStickMove < STICK_REPEAT_MS) return true
        val consumed = when {
            y < -STICK_DEADZONE -> moveFocusStep(-1)
            y > STICK_DEADZONE -> moveFocusStep(+1)
            x < -STICK_DEADZONE -> nudgeSlider(-1)
            x > STICK_DEADZONE -> nudgeSlider(+1)
            else -> false
        }
        if (consumed) lastStickMove = now
        return consumed
    }

    private fun focusFirstItem(): Boolean =
        barItems().firstOrNull()?.let { it.post { it.requestFocus() }; true } ?: false

    /** Moves focus [step] items along the column (wrapping), independent of on-screen geometry. */
    private fun moveFocusStep(step: Int): Boolean {
        val items = barItems()
        if (items.isEmpty()) return false
        val idx = items.indexOf(view?.findFocus())
        val next = if (idx < 0) 0 else (idx + step + items.size) % items.size
        return items[next].requestFocus()
    }

    /** Up/down on a focused SeekBar nudges it (and commits); otherwise returns false. */
    private fun nudgeSlider(delta: Int): Boolean {
        val sb = view?.findFocus() as? SeekBar ?: return false
        sb.progress = (sb.progress + delta * SEEKBAR_STEP).coerceIn(0, sb.max)
        // Programmatic setProgress doesn't fire fromUser, so commit the persisted value directly.
        when (sb.id) {
            R.id.curveSlider -> SpatialControls.setPanelCurve(requireContext(), sb.progress / 100f)
            R.id.scaleSlider -> SpatialControls.setPanelScale(requireContext(), progressToScale(sb.progress))
        }
        return true
    }

    companion object {
        private const val SLOT_COUNT = 2
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val MAX_LOG_LINES = 400
        private const val PREFS_NAME = "camera_eyes"
        private const val PREF_ASPECT_MODE = "aspect_mode"
        private const val PREF_SWAPPED = "panes_swapped"
        private const val PREF_ROTATION = "rotation_deg"
        private const val PREF_FLIP_H = "flip_horizontal"
        private const val SWAP_REOPEN_DELAY_MS = 300L
        private const val OPEN_RETRY_BASE_MS = 600L   // retry delay per attempt (multiplied by attempt#)
        private const val MAX_REOPEN_ATTEMPTS = 3

        // Controller/gamepad tuning for the settings bar.
        private const val STICK_DEADZONE = 0.5f       // stick magnitude before a focus move fires
        private const val STICK_REPEAT_MS = 250L      // min gap between stick-driven focus moves
        private const val SEEKBAR_STEP = 5            // progress units per D-pad/stick nudge
    }
}
