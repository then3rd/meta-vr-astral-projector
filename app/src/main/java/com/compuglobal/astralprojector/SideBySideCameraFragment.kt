package com.compuglobal.astralprojector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.MultiCameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import java.io.File
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

    /** Background thread for the periodic state dump; quit on view teardown. */
    private var dumpThread: HandlerThread? = null

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

    /** True when logLines changed while the overlay was hidden, so its text needs a rebuild. */
    private var logTextStale = false

    // Settings controls row (toggled by the gear button; hidden by default).
    private var settingsList: ViewGroup? = null
    private var settingsScroll: View? = null
    private var settingsScrim: View? = null
    private var settingsToggle: TextView? = null
    private var statusReadout: TextView? = null
    private var statusReadoutLeft: TextView? = null
    private var statusReadoutRight: TextView? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Transparent spacer between the panes; its layout weight sets the passthrough gap. */
    private var gapSpacer: View? = null

    // Recording: each open USB camera records itself via AUSBC's captureVideoStart (H.264 + mic
    // AAC -> MP4). Not persisted — recording never survives a relaunch.
    private var recording = false
    private var recordToggle: TextView? = null
    private var passthroughRecorder: PassthroughRecorder? = null

    // AUSBC's Mp4Muxer only starts once BOTH its video and audio tracks arrive, so without the
    // mic permission the per-camera files would stay empty forever — request it on first Record.
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            FileLogger.log("record: RECORD_AUDIO request -> granted=$granted")
            startRecording(audioGranted = granted)
        }

    // Snapshot of the stereo pref at view creation. The panel (and with it this fragment) is
    // recreated whenever the pref flips, so the flag is stable for the view's lifetime: in stereo
    // all full-width overlays stay hidden (each eye sees only half the surface, so a full-width
    // UI would show a different half to each eye). A duplicated per-eye settings column
    // (gear button / MENU / Y) exposes scale, gap, swap and exit; B/BACK drops back to mono.
    private var stereoOn = false

    // Stereo-only controls: a compact settings column duplicated once per half at identical
    // positions so the eyes fuse the two copies into one menu. The list holds the two half
    // containers (left first — controller focus navigation runs on the left copy).
    private var stereoOverlay: View? = null
    private var stereoGearRow: View? = null
    private var stereoHalves: List<View> = emptyList()

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
            if (show) refreshLogText()
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
        root.findViewById<TextView>(R.id.resetButton).setOnClickListener { resetAllSettings() }

        // Clock + battery readout, left of the settings button. Re-posts itself once a second via
        // mainHandler, which onDestroyView already clears (removeCallbacksAndMessages(null)).
        statusReadout = root.findViewById(R.id.statusReadout)
        statusReadoutLeft = root.findViewById(R.id.statusReadoutLeft)
        statusReadoutRight = root.findViewById(R.id.statusReadoutRight)
        startStatusReadoutTicker()

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

        // Binocular mode: left camera -> left eye, right camera -> right eye. Writing the pref
        // makes ImmersiveActivity recreate the panel with StereoMode.LeftRight, which finishes
        // and relaunches this fragment's activity — so the button only ever turns stereo ON
        // (the settings menu is unreachable while stereo is active; MENU/Y exits instead).
        stereoOn = SpatialControls.isStereoEnabled(requireContext())
        root.findViewById<TextView>(R.id.stereoToggle).setOnClickListener {
            FileLogger.log("stereo toggle tapped -> on (panel will recreate)")
            // Each eye only sees half the surface, so stereo wants a larger panel — default it to 150%.
            SpatialControls.setPanelScale(requireContext(), SpatialControls.DEFAULT_STEREO_SCALE)
            SpatialControls.setStereoEnabled(requireContext(), true)
        }

        recordToggle = root.findViewById(R.id.recordToggle)
        recordToggle?.setOnClickListener { toggleRecording() }

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

        // Gap slider: 0..100% maps to the transparent spacer's layout weight, widening the
        // passthrough-visible gap between the two panes (100% = gap takes half the row width).
        // Pure view layout — cheap enough to apply live on every progress tick.
        gapSpacer = root.findViewById(R.id.gapSpacer)
        val gapLabel = root.findViewById<TextView>(R.id.gapLabel)
        val gapSlider = root.findViewById<SeekBar>(R.id.gapSlider)
        val initialGap = loadGapPct()
        gapSlider.progress = initialGap
        gapLabel.text = str(R.string.gap_label, initialGap)
        applyGap(initialGap)
        gapSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                gapLabel.text = str(R.string.gap_label, progress)
                if (fromUser) {
                    applyGap(progress)
                    saveGapPct(progress)
                }
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

        if (stereoOn) {
            root.findViewById<View>(R.id.topBar)?.visibility = View.GONE
            root.findViewById<View>(R.id.curveTile)?.visibility = View.GONE
            settingsScroll?.visibility = View.GONE
            settingsScrim?.visibility = View.GONE
            logOverlay?.visibility = View.GONE
            wireStereoSettings(root)
            FileLogger.log("stereo mode active: per-eye settings wired, B/BACK exits to mono")
        }
        // Stream every log line (including ones buffered before now) to the overlay.
        FileLogger.setListener { line -> appendLog(line) }
        return root
    }

    /**
     * Wires the stereo-only controls: a gear button and a compact settings column (scale, gap,
     * swap, exit), each duplicated once per half of the surface so both eyes see an identical,
     * fusable copy. Acting on either copy commits the change and mirrors the other copy's state.
     */
    private fun wireStereoSettings(root: View) {
        stereoOverlay = root.findViewById(R.id.stereoOverlay)
        stereoGearRow = root.findViewById<View>(R.id.stereoGearRow).also { it.visibility = View.VISIBLE }
        val halves = listOf<View>(
            root.findViewById(R.id.stereoHalfLeft),
            root.findViewById(R.id.stereoHalfRight),
        )
        stereoHalves = halves
        listOf<TextView>(root.findViewById(R.id.stereoGearLeft), root.findViewById(R.id.stereoGearRight))
            .forEach { gear -> gear.setOnClickListener { toggleSettings() } }
        // Exit lives next to the gear (mirroring how Enable Stereo sits next to Settings in mono),
        // so leaving stereo doesn't require opening the menu first.
        listOf<TextView>(root.findViewById(R.id.stereoExitLeft), root.findViewById(R.id.stereoExitRight))
            .forEach { exit ->
                exit.setOnClickListener {
                    FileLogger.log("exit stereo tapped -> mono (panel will recreate)")
                    // Undo the 150% stereo default so mono comes back at its own 100% default.
                    SpatialControls.setPanelScale(requireContext(), SpatialControls.DEFAULT_PANEL_SCALE)
                    SpatialControls.setStereoEnabled(requireContext(), false)
                }
            }

        val ctx = requireContext()
        val scaleSliders = halves.map { it.findViewById<SeekBar>(R.id.stScaleSlider) }
        val scaleLabels = halves.map { it.findViewById<TextView>(R.id.stScaleLabel) }
        val gapSliders = halves.map { it.findViewById<SeekBar>(R.id.stGapSlider) }
        val gapLabels = halves.map { it.findViewById<TextView>(R.id.stGapLabel) }

        val initialScale = SpatialControls.getPanelScale(ctx)
        val initialGap = loadGapPct()
        scaleSliders.forEach { it.progress = scaleToProgress(initialScale) }
        scaleLabels.forEach { it.text = str(R.string.scale_label, (initialScale * 100f).toInt()) }
        gapSliders.forEach { it.progress = initialGap }
        gapLabels.forEach { it.text = str(R.string.gap_label, initialGap) }

        scaleSliders.forEachIndexed { i, slider ->
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val scale = progressToScale(progress)
                    scaleLabels.forEach { it.text = str(R.string.scale_label, (scale * 100f).toInt()) }
                    if (fromUser) {
                        SpatialControls.setPanelScale(requireContext(), scale)
                        // Mirror the other eye's copy (programmatic setProgress won't recurse:
                        // it arrives with fromUser = false).
                        scaleSliders[1 - i].progress = progress
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        gapSliders.forEachIndexed { i, slider ->
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    gapLabels.forEach { it.text = str(R.string.gap_label, progress) }
                    if (fromUser) {
                        applyGap(progress)
                        saveGapPct(progress)
                        gapSliders[1 - i].progress = progress
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        // Toggle rows duplicated per eye. Each acts on shared state, then refreshes BOTH copies'
        // labels so the fused menu stays consistent. Curve is deliberately absent — stereo is a
        // flat quad (ImmersiveActivity.applyPanelCurve early-returns while stereo is on).
        val rotationBtns = halves.map { it.findViewById<TextView>(R.id.stRotation) }
        rotationBtns.forEach { it.text = str(R.string.rotation_label, rotationDeg) }
        rotationBtns.forEach { btn ->
            btn.setOnClickListener {
                rotationDeg = (rotationDeg + 90) % 360
                saveRotation(rotationDeg)
                applyAspectToAll()
                rotationBtns.forEach { it.text = str(R.string.rotation_label, rotationDeg) }
                FileLogger.log("stereo rotation -> $rotationDeg")
            }
        }

        val flipBtns = halves.map { it.findViewById<TextView>(R.id.stFlip) }
        flipBtns.forEach { it.text = str(if (flipH) R.string.flip_h_on else R.string.flip_h_off) }
        flipBtns.forEach { btn ->
            btn.setOnClickListener {
                flipH = !flipH
                saveFlipH(flipH)
                applyAspectToAll()
                flipBtns.forEach { it.text = str(if (flipH) R.string.flip_h_on else R.string.flip_h_off) }
                FileLogger.log("stereo flipH -> $flipH")
            }
        }

        val aspectBtns = halves.map { it.findViewById<TextView>(R.id.stAspect) }
        aspectBtns.forEach { it.text = str(aspectMode.labelRes) }
        aspectBtns.forEach { btn ->
            btn.setOnClickListener {
                aspectMode = AspectMode.values()[(aspectMode.ordinal + 1) % AspectMode.values().size]
                saveAspectMode(aspectMode)
                applyAspectToAll()
                aspectBtns.forEach { it.text = str(aspectMode.labelRes) }
                FileLogger.log("stereo aspect -> $aspectMode")
            }
        }

        val followBtns = halves.map { it.findViewById<TextView>(R.id.stFollow) }
        followBtns.forEach { it.text = str(if (SpatialControls.isHeadFollowEnabled(ctx)) R.string.head_follow_on else R.string.head_follow_off) }
        followBtns.forEach { btn ->
            btn.setOnClickListener {
                val next = !SpatialControls.isHeadFollowEnabled(requireContext())
                SpatialControls.setHeadFollowEnabled(requireContext(), next)
                followBtns.forEach { it.text = str(if (next) R.string.head_follow_on else R.string.head_follow_off) }
                FileLogger.log("stereo headFollow -> $next")
            }
        }

        val smoothBtns = halves.map { it.findViewById<TextView>(R.id.stSmoothing) }
        smoothBtns.forEach { it.text = str(if (SpatialControls.isSmoothingEnabled(ctx)) R.string.smoothing_on else R.string.smoothing_off) }
        smoothBtns.forEach { btn ->
            btn.setOnClickListener {
                val next = !SpatialControls.isSmoothingEnabled(requireContext())
                SpatialControls.setSmoothingEnabled(requireContext(), next)
                smoothBtns.forEach { it.text = str(if (next) R.string.smoothing_on else R.string.smoothing_off) }
                FileLogger.log("stereo smoothing -> $next")
            }
        }

        val passthroughBtns = halves.map { it.findViewById<TextView>(R.id.stPassthrough) }
        passthroughBtns.forEach { it.text = str(if (SpatialControls.isPassthroughEnabled(ctx)) R.string.passthrough_on else R.string.passthrough_off) }
        passthroughBtns.forEach { btn ->
            btn.setOnClickListener {
                val next = !SpatialControls.isPassthroughEnabled(requireContext())
                SpatialControls.setPassthroughEnabled(requireContext(), next)
                passthroughBtns.forEach { it.text = str(if (next) R.string.passthrough_on else R.string.passthrough_off) }
                FileLogger.log("stereo passthrough -> $next")
            }
        }

        halves.forEach { half ->
            half.findViewById<TextView>(R.id.stSwap).setOnClickListener { performSwap() }
        }

        // Record start/stop, duplicated per eye like everything else; updateRecordLabels()
        // refreshes both copies (and the hidden mono button) whenever the state flips.
        halves.forEach { half ->
            half.findViewById<TextView>(R.id.stRecord).setOnClickListener { toggleRecording() }
        }
    }

    // --- recording ---------------------------------------------------------------

    private fun toggleRecording() {
        if (recording) {
            stopRecording()
            return
        }
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording(audioGranted = true)
        } else {
            FileLogger.log("record: requesting RECORD_AUDIO")
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Starts one MP4 per open USB camera (named after the pane it's displayed in, so the file
     * matches what the user sees post-swap), plus an optional passthrough recording on Quest 3/3S
     * (Horizon OS v74+ only — silently skipped on Quest 2 where camera2 exposes no devices).
     * Files land in /sdcard/Recordings or the app-private Movies dir:
     * REC_<timestamp>_{left,right,passthrough}.mp4
     */
    private fun startRecording(audioGranted: Boolean) {
        if (recording) return
        val ctx = appCtx ?: context?.applicationContext ?: return
        val dir = recordingsDir(ctx)
        val base = "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // AUSBC's captureVideoStartInternal bails out (onError, no file ever created) unless
        // checkSelfPermission(WRITE_EXTERNAL_STORAGE) passes — even though scoped storage makes
        // the permission itself meaningless. It can't be granted via a runtime dialog on
        // targetSdk 30+ (auto-denied); it must be pm-granted like the other recording perms.
        val storageGranted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!storageGranted) {
            FileLogger.log(
                "record: WRITE_EXTERNAL_STORAGE not granted — AUSBC refuses per-camera recording. " +
                    "Fix: adb shell pm grant com.compuglobal.astralprojector " +
                    "android.permission.WRITE_EXTERNAL_STORAGE"
            )
            return
        }
        if (!audioGranted) {
            FileLogger.log("record: RECORD_AUDIO denied — AUSBC's muxer stalls without the mic track")
            return
        }
        var started = 0
        for (idx in 0 until SLOT_COUNT) {
            val cam = slots[idx] ?: continue
            if (!runCatching { cam.isCameraOpened() }.getOrDefault(false)) continue
            val side = if (displayIndexFor(idx) == 0) "left" else "right"
            // Mp4Muxer appends ".mp4" itself, so the path is passed extension-less.
            val path = File(dir, "${base}_$side").absolutePath
            FileLogger.log("record: start slot=$idx ($side) -> $path.mp4")
            runCatching { cam.captureVideoStart(recordCallback(side), path, 0L) }
                .onFailure { FileLogger.log("record: captureVideoStart($side) FAILED", it) }
                .onSuccess { started++ }
        }
        if (started == 0) {
            FileLogger.log("record: no open USB camera to record")
            return
        }
        // Passthrough recording: Quest 3/3S + Horizon OS v74+ only. Any failure (missing
        // permission, no camera2 devices on Quest 2, API not present) is caught and logged so
        // it never blocks the USB camera recordings above.
        runCatching {
            val pt = passthroughRecorder ?: PassthroughRecorder(ctx).also { passthroughRecorder = it }
            pt.start(File(dir, "${base}_passthrough.mp4"))
        }.onFailure { FileLogger.log("record: passthrough recorder failed to start", it) }

        recording = true
        updateRecordLabels()
    }

    private fun stopRecording() {
        if (!recording) return
        FileLogger.log("record: stop")
        // captureVideoStop on a camera that isn't recording is a no-op, so blanket-stop all slots.
        slots.forEach { cam -> runCatching { cam?.captureVideoStop() } }
        runCatching { passthroughRecorder?.stop() }
            .onFailure { FileLogger.log("record: passthrough stop failed", it) }
        recording = false
        updateRecordLabels()
    }

    /**
     * Where recordings land. Preferred: the shared /sdcard/Recordings folder — "This headset /
     * Recordings" in the Quest Files app — which needs the MANAGE_EXTERNAL_STORAGE appop
     * (`adb shell appops set --uid com.compuglobal.astralprojector MANAGE_EXTERNAL_STORAGE allow`;
     * scoped storage allows no other write path there for video files). Without the grant, falls
     * back to the app-private Movies dir (Android/data/<pkg>/files/Movies), which needs no
     * permission but is only reachable via adb/MTP.
     */
    private fun recordingsDir(ctx: Context): File {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            val shared = File(Environment.getExternalStorageDirectory(), "Recordings")
            if (shared.isDirectory || shared.mkdirs()) return shared
            FileLogger.log("record: shared dir $shared not creatable; falling back to app dir")
        } else {
            FileLogger.log("record: no all-files access (adb: appops set --uid <pkg> MANAGE_EXTERNAL_STORAGE allow) — using app-private dir")
        }
        return ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
    }

    /** AUSBC invokes these on the main thread (Mp4Muxer marshals via its main handler). */
    private fun recordCallback(side: String) = object : ICaptureCallBack {
        override fun onBegin() = FileLogger.log("record($side): began")
        override fun onError(error: String?) = FileLogger.log("record($side): ERROR ${error ?: "unknown"}")
        override fun onComplete(path: String?) {
            FileLogger.log("record($side): saved $path")
            // Register with MediaStore so the Files app / gallery sees it without a reboot.
            path?.let { scanRecording(it) }
        }
    }

    private fun scanRecording(path: String) {
        val ctx = appCtx ?: return
        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
    }

    private fun updateRecordLabels() {
        val label = str(if (recording) R.string.record_stop else R.string.record_start)
        recordToggle?.text = label
        stereoHalves.forEach { it.findViewById<TextView>(R.id.stRecord)?.text = label }
    }

    /** FileLogger invokes this from arbitrary threads; marshal to the UI thread. */
    private fun appendLog(line: String) {
        mainHandler.post {
            logLines.addLast(line)
            var trimmed = false
            while (logLines.size > MAX_LOG_LINES) { logLines.removeFirst(); trimmed = true }
            val tv = logText ?: return@post
            // While the overlay is hidden, only the deque is maintained — rebuilding a ~400-line
            // string and re-laying-out the TextView per log line is main-thread work for nothing.
            if (logOverlay?.visibility != View.VISIBLE) {
                logTextStale = true
                return@post
            }
            if (logTextStale || trimmed) {
                tv.text = logLines.joinToString("\n")
                logTextStale = false
            } else {
                if (tv.text.isNotEmpty()) tv.append("\n")
                tv.append(line)
            }
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Rebuilds the overlay text from the deque if lines arrived while it was hidden. */
    private fun refreshLogText() {
        val tv = logText ?: return
        if (!logTextStale) return
        tv.text = logLines.joinToString("\n")
        logTextStale = false
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
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
        // Runs on its own thread: each tick makes several Binder calls (device list, USB
        // permission checks) whose latency would otherwise land on the main thread every 3 s —
        // observed as a periodic pane stutter during the first ~40 s of a session.
        val thread = HandlerThread("StateDump").also { it.start() }
        dumpThread = thread
        val handler = Handler(thread.looper)
        handler.postDelayed(object : Runnable {
            override fun run() {
                runCatching {
                    val devices = runCatching { getDeviceList() }.getOrNull()
                    val sb = StringBuilder("state-dump #${dumps}: deviceList=${devices?.size ?: "null"} mapSize=${getCameraMap().size}")
                    getCameraMap().values.toList().forEach { cam ->
                        val d = cam.getUsbDevice()
                        sb.append("\n    - ${desc(d)} hasPermission=${safeHasPermission(d)} opened=${runCatching { cam.isCameraOpened() }.getOrNull()}")
                    }
                    FileLogger.log(sb.toString())
                }.onFailure { FileLogger.log("state-dump failed", it) }
                if (++dumps < 12) handler.postDelayed(this, 3000) else thread.quitSafely()
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
        // Swapping close/reopens both cameras, which tears down their encoders mid-file — finish
        // the recordings cleanly first rather than leaving truncated MP4s.
        if (recording) stopRecording()
        swapped = !swapped
        FileLogger.log("swap -> $swapped")
        saveSwapped(swapped)
        for (logicalIdx in 0 until SLOT_COUNT) {
            val camera = slots[logicalIdx] ?: continue
            reopenOnDisplay(camera, logicalIdx)
        }
    }

    /**
     * Updates the clock + battery readout and re-posts itself once a second. Runs on mainHandler,
     * which onDestroyView clears via removeCallbacksAndMessages(null), so this stops on teardown
     * without an explicit flag.
     */
    private fun startStatusReadoutTicker() {
        val tick = object : Runnable {
            override fun run() {
                // Guard on statusReadout (cleared in onDestroyView), not getView(): this is first
                // invoked synchronously from getRootView(), before the fragment's own view field
                // is assigned, so checking view == null here would abort on the very first tick.
                if (statusReadout == null) return
                val time = timeFormat.format(Date())
                val battery = requireContext().getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val base = if (battery != null && battery in 0..100) "$time  $battery%" else time
                val text = if (recording) "⏺ $base" else base
                statusReadout?.text = text
                statusReadoutLeft?.text = text
                statusReadoutRight?.text = text
                mainHandler.postDelayed(this, STATUS_READOUT_INTERVAL_MS)
            }
        }
        tick.run()
    }

    /**
     * Restores every setting to its default: the pane prefs this fragment owns (rotation, flip,
     * aspect, gap, swap) and the shared spatial controls (whose setters fire ImmersiveActivity's
     * pref listener, re-applying follow/smoothing/passthrough/scale/curve live). Also refreshes
     * the settings-menu labels and sliders so the UI reflects the restored values.
     */
    private fun resetAllSettings() {
        val root = view ?: return
        FileLogger.log("reset all settings")

        rotationDeg = 0
        saveRotation(rotationDeg)
        rotationToggle?.text = str(R.string.rotation_label, rotationDeg)
        flipH = false
        saveFlipH(flipH)
        flipToggle?.text = str(R.string.flip_h_off)
        aspectMode = AspectMode.FULL_FRAME
        saveAspectMode(aspectMode)
        aspectToggle?.text = str(aspectMode.labelRes)
        applyAspectToAll()
        saveGapPct(0)
        applyGap(0)
        // Programmatic setProgress updates the sliders' labels via onProgressChanged, but with
        // fromUser=false it writes no prefs — the setters above/below are the commits.
        root.findViewById<SeekBar>(R.id.gapSlider)?.progress = 0
        if (swapped) performSwap()

        val ctx = requireContext()
        SpatialControls.setHeadFollowEnabled(ctx, SpatialControls.DEFAULT_HEAD_FOLLOW)
        SpatialControls.setSmoothingEnabled(ctx, SpatialControls.DEFAULT_SMOOTHING)
        SpatialControls.setPassthroughEnabled(ctx, SpatialControls.DEFAULT_PASSTHROUGH)
        SpatialControls.setStereoEnabled(ctx, SpatialControls.DEFAULT_STEREO)
        SpatialControls.setPanelDistance(ctx, SpatialControls.DEFAULT_PANEL_DISTANCE)
        SpatialControls.setPanelScale(ctx, SpatialControls.DEFAULT_PANEL_SCALE)
        SpatialControls.setPanelCurve(ctx, SpatialControls.DEFAULT_PANEL_CURVE)
        root.findViewById<TextView>(R.id.headFollowToggle)?.text =
            str(if (SpatialControls.DEFAULT_HEAD_FOLLOW) R.string.head_follow_on else R.string.head_follow_off)
        root.findViewById<TextView>(R.id.smoothingToggle)?.text =
            str(if (SpatialControls.DEFAULT_SMOOTHING) R.string.smoothing_on else R.string.smoothing_off)
        root.findViewById<TextView>(R.id.passthroughToggle)?.text =
            str(if (SpatialControls.DEFAULT_PASSTHROUGH) R.string.passthrough_on else R.string.passthrough_off)
        root.findViewById<SeekBar>(R.id.scaleSlider)?.progress =
            scaleToProgress(SpatialControls.DEFAULT_PANEL_SCALE)
        root.findViewById<SeekBar>(R.id.curveSlider)?.progress =
            (SpatialControls.DEFAULT_PANEL_CURVE * 100f).toInt()
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

    private fun loadGapPct(): Int =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_GAP_PCT, 0).coerceIn(0, 100)

    private fun saveGapPct(value: Int) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(PREF_GAP_PCT, value).apply()
    }

    /**
     * Gap percent -> spacer layout weight. Panes have weight 1 each, so weight w gives the
     * spacer w/(2+w) of the row: 100% -> weight 2 -> half the row width. The panes' own
     * layout-change listeners re-run applyAspect when they resize.
     */
    private fun applyGap(pct: Int) {
        val spacer = gapSpacer ?: return
        val lp = spacer.layoutParams as LinearLayout.LayoutParams
        // In stereo the gap shifts each eye's image outward (a divergence/alignment trim), so a
        // much smaller range is both sufficient and safer for eye comfort.
        lp.weight = pct / 100f * (if (stereoOn) MAX_GAP_WEIGHT_STEREO else MAX_GAP_WEIGHT)
        spacer.layoutParams = lp
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
        stopRecording()
        recordToggle = null
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
        statusReadout = null
        statusReadoutLeft = null
        statusReadoutRight = null
        stereoOverlay = null
        stereoGearRow = null
        stereoHalves = emptyList()
        mainHandler.removeCallbacksAndMessages(null)
        dumpThread?.quitSafely()
        dumpThread = null
        super.onDestroyView()
    }

    // --- settings toggle + controller input -------------------------------------
    //
    // A gear button above the video toggles the (highly transparent) controls row, which is hidden
    // by default. A Quest controller can also drive it: MENU/Y opens the controls and focuses them
    // (or hides them if already open), then left/right step across items, up/down nudge a focused
    // slider, and A/center clicks. Navigation keys are only intercepted while the controls hold
    // focus, so normal pointer/hand-ray use of the camera view is unaffected.

    fun isSettingsVisible(): Boolean =
        if (stereoOn) stereoOverlay?.visibility == View.VISIBLE
        else settingsScroll?.visibility == View.VISIBLE

    fun toggleSettings() = setSettingsVisible(!isSettingsVisible())

    private fun setSettingsVisible(show: Boolean) {
        if (stereoOn) {
            stereoOverlay?.visibility = if (show) View.VISIBLE else View.GONE
            FileLogger.log("stereo settings ${if (show) "shown" else "hidden"}")
            if (!show) view?.findFocus()?.clearFocus()
            return
        }
        val scroll = settingsScroll ?: return
        scroll.visibility = if (show) View.VISIBLE else View.GONE
        settingsScrim?.visibility = if (show) View.VISIBLE else View.GONE
        settingsToggle?.text = str(if (show) R.string.settings_close else R.string.settings_open)
        FileLogger.log("settings controls ${if (show) "shown" else "hidden"}")
        if (!show) view?.findFocus()?.clearFocus()
    }

    /** Ordered list of focusable controls in the row (buttons + sliders), left to right. */
    private fun barItems(): List<View> {
        // In stereo, controller navigation runs on the LEFT copy of the duplicated column; the
        // change handlers mirror everything onto the right copy.
        val list = (if (stereoOn) stereoHalves.firstOrNull() else settingsList) ?: return emptyList()
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

    // ---- adb broadcast entry points (called by MainActivity.debugReceiver) ------

    fun toggleRecordingFromAdb() = activity?.runOnUiThread { toggleRecording() }
    fun startRecordingFromAdb() = activity?.runOnUiThread { if (!recording) toggleRecording() }
    fun stopRecordingFromAdb()  = activity?.runOnUiThread { if (recording)  stopRecording() }
    fun openSettingsFromAdb()   = activity?.runOnUiThread { setSettingsVisible(true) }
    fun resetToDefaultsFromAdb() = activity?.runOnUiThread { resetAllSettings() }

    fun handleControllerKey(keyCode: Int): Boolean {
        // In stereo, B/BACK with the settings closed drops back to mono (recreating the panel
        // with the full UI). MENU/Y and the rest fall through to the shared settings handling,
        // which operates on the duplicated per-eye column via the stereo-aware helpers.
        if (stereoOn && !isSettingsVisible() &&
            (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            FileLogger.log("controller exit stereo -> mono (panel will recreate)")
            // Undo the 150% stereo default so mono comes back at its own 100% default.
            SpatialControls.setPanelScale(requireContext(), SpatialControls.DEFAULT_PANEL_SCALE)
            SpatialControls.setStereoEnabled(requireContext(), false)
            return true
        }
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
            R.id.scaleSlider, R.id.stScaleSlider ->
                SpatialControls.setPanelScale(requireContext(), progressToScale(sb.progress))
            R.id.gapSlider, R.id.stGapSlider -> {
                applyGap(sb.progress)
                saveGapPct(sb.progress)
            }
        }
        mirrorStereoSlider(sb)
        return true
    }

    /** Copies a stereo slider's progress to its twin in the other eye's column. */
    private fun mirrorStereoSlider(sb: SeekBar) {
        if (!stereoOn) return
        stereoHalves.forEach { half ->
            half.findViewById<SeekBar>(sb.id)?.takeIf { it !== sb }?.progress = sb.progress
        }
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
        private const val PREF_GAP_PCT = "pane_gap_pct"
        private const val MAX_GAP_WEIGHT = 2f         // gap slider at 100% = gap takes half the row
        private const val MAX_GAP_WEIGHT_STEREO = 0.5f // stereo: gap is a divergence trim, keep small
        private const val SWAP_REOPEN_DELAY_MS = 300L
        private const val OPEN_RETRY_BASE_MS = 600L   // retry delay per attempt (multiplied by attempt#)
        private const val MAX_REOPEN_ATTEMPTS = 3

        // Controller/gamepad tuning for the settings bar.
        private const val STICK_DEADZONE = 0.5f       // stick magnitude before a focus move fires
        private const val STICK_REPEAT_MS = 250L      // min gap between stick-driven focus moves
        private const val SEEKBAR_STEP = 5            // progress units per D-pad/stick nudge
        private const val STATUS_READOUT_INTERVAL_MS = 1000L
    }
}
