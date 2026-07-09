package com.adiglobal.cameraeyes

import android.content.Context
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.MultiCameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.widget.AspectRatioTextureView

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

    /** Negotiated preview size per slot (may differ from the requested one, e.g. 640x480). */
    private val videoSizes = arrayOfNulls<PreviewSize>(SLOT_COUNT)

    private var aspectMode = AspectMode.STRETCH
    private var aspectToggle: TextView? = null

    // Whether the left/right panes are swapped; left/right slot depends on hub port,
    // not on which physical camera unit connected, so this lets the user correct it on-screen.
    private var swapped = false
    private lateinit var paneContainer: LinearLayout
    private lateinit var paneLeft: FrameLayout
    private lateinit var paneRight: FrameLayout

    private lateinit var textures: Array<AspectRatioTextureView>
    private lateinit var statuses: Array<TextView>

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dumps = 0

    // On-screen log overlay (retrievable without ADB or a card reader).
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private val logLines = ArrayDeque<String>()

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        FileLogger.log("getRootView")
        val root = inflater.inflate(R.layout.fragment_side_by_side, container, false)
        textures = arrayOf(root.findViewById(R.id.textureLeft), root.findViewById(R.id.textureRight))
        statuses = arrayOf(root.findViewById(R.id.statusLeft), root.findViewById(R.id.statusRight))

        logScroll = root.findViewById(R.id.logScroll)
        logText = root.findViewById(R.id.logText)
        val logToggle = root.findViewById<TextView>(R.id.logToggle)
        logToggle.setOnClickListener {
            val show = logScroll?.visibility != View.VISIBLE
            logScroll?.visibility = if (show) View.VISIBLE else View.GONE
            logToggle.text = getString(if (show) R.string.log_hide else R.string.log_show)
        }

        aspectMode = loadAspectMode()
        val aspectBtn = root.findViewById<TextView>(R.id.aspectToggle)
        aspectToggle = aspectBtn
        aspectBtn.text = getString(aspectMode.labelRes)
        aspectBtn.setOnClickListener {
            aspectMode = AspectMode.values()[(aspectMode.ordinal + 1) % AspectMode.values().size]
            FileLogger.log("aspect mode -> $aspectMode")
            saveAspectMode(aspectMode)
            aspectBtn.text = getString(aspectMode.labelRes)
            applyAspectToAll()
        }

        paneContainer = root.findViewById(R.id.paneContainer)
        paneLeft = root.findViewById(R.id.paneLeft)
        paneRight = root.findViewById(R.id.paneRight)
        swapped = loadSwapped()
        applyPaneOrder()
        root.findViewById<TextView>(R.id.swapToggle).setOnClickListener {
            swapped = !swapped
            FileLogger.log("swap panes -> $swapped")
            saveSwapped(swapped)
            applyPaneOrder()
        }
        // Pane size settles after first layout (and can change); recompute the transform then.
        textures.forEachIndexed { idx, tv ->
            tv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) applyAspect(idx)
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
        if (idx >= 0) setStatus(idx, getString(R.string.status_connecting))
    }

    override fun onCameraDetached(camera: MultiCameraClient.Camera) {
        FileLogger.log("onCameraDetached ${desc(camera.getUsbDevice())}")
        val idx = slotOf(camera)
        if (idx >= 0) {
            slots[idx] = null
            videoSizes[idx] = null
            setStatus(idx, getString(R.string.status_waiting))
        }
        runCatching { camera.closeCamera() }.onFailure { FileLogger.log("closeCamera(detach) failed", it) }
    }

    override fun onCameraConnected(camera: MultiCameraClient.Camera) {
        val d = camera.getUsbDevice()
        FileLogger.log("onCameraConnected ${desc(d)}")
        val idx = firstFreeSlot()
        if (idx < 0) {
            FileLogger.log("no free slot; ignoring extra camera ${desc(d)}")
            setStatus(SLOT_COUNT - 1, getString(R.string.status_extra))
            runCatching { camera.closeCamera() }
            return
        }
        slots[idx] = camera
        camera.setCameraStateCallBack(this)
        try {
            FileLogger.log("openCamera slot=$idx ${PREVIEW_WIDTH}x$PREVIEW_HEIGHT")
            camera.openCamera(textures[idx], buildRequest())
            setStatus(idx, getString(R.string.status_opening))
        } catch (t: Throwable) {
            FileLogger.log("openCamera slot=$idx FAILED", t)
            setStatus(idx, getString(R.string.status_error, t.message ?: "openCamera threw"))
        }
        requestPermissionForPendingCameras()
    }

    override fun onCameraDisConnected(camera: MultiCameraClient.Camera) {
        FileLogger.log("onCameraDisConnected ${desc(camera.getUsbDevice())}")
        val idx = slotOf(camera)
        if (idx >= 0) {
            slots[idx] = null
            videoSizes[idx] = null
            setStatus(idx, getString(R.string.status_disconnected))
        }
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
                hideStatus(idx)
                // getPreviewSize() holds the size UVC actually negotiated, which can differ
                // from the requested 1280x720 (640x480 observed on Quest 2).
                videoSizes[idx] = runCatching { self.getPreviewSize() }.getOrNull()
                FileLogger.log("slot=$idx negotiated previewSize=${videoSizes[idx]?.width}x${videoSizes[idx]?.height}")
                applyAspect(idx)
            }
            ICameraStateCallBack.State.CLOSED -> setStatus(idx, getString(R.string.status_disconnected))
            ICameraStateCallBack.State.ERROR ->
                setStatus(idx, getString(R.string.status_error, msg ?: "unknown"))
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
     */
    private fun applyAspect(idx: Int) {
        if (!::textures.isInitialized) return
        val tv = textures[idx]
        tv.post {
            val viewW = tv.width.toFloat()
            val viewH = tv.height.toFloat()
            val size = videoSizes[idx]
            if (viewW <= 0f || viewH <= 0f || size == null || size.width <= 0 || size.height <= 0) {
                tv.setTransform(null)
                return@post
            }
            val videoAr = size.width.toFloat() / size.height
            val viewAr = viewW / viewH
            var sx = 1f
            var sy = 1f
            when (aspectMode) {
                AspectMode.STRETCH -> Unit
                AspectMode.FIT_WIDTH -> sy = viewAr / videoAr
                AspectMode.FIT_HEIGHT -> sx = videoAr / viewAr
                AspectMode.FULL_FRAME ->
                    if (videoAr > viewAr) sy = viewAr / videoAr else sx = videoAr / viewAr
            }
            tv.setTransform(Matrix().apply { setScale(sx, sy, viewW / 2f, viewH / 2f) })
        }
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

    // Reorders the pane container's children so paneLeft/paneRight visually swap sides,
    // without touching camera<->TextureView bindings (both panes stay attached to the window).
    private fun applyPaneOrder() {
        if (!::paneContainer.isInitialized) return
        paneContainer.removeView(paneLeft)
        paneContainer.removeView(paneRight)
        if (swapped) {
            paneContainer.addView(paneRight, 0)
            paneContainer.addView(paneLeft)
        } else {
            paneContainer.addView(paneLeft, 0)
            paneContainer.addView(paneRight)
        }
    }

    private fun loadSwapped(): Boolean =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_SWAPPED, false)

    private fun saveSwapped(value: Boolean) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_SWAPPED, value).apply()
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
        aspectToggle = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    companion object {
        private const val SLOT_COUNT = 2
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val MAX_LOG_LINES = 400
        private const val PREFS_NAME = "camera_eyes"
        private const val PREF_ASPECT_MODE = "aspect_mode"
        private const val PREF_SWAPPED = "panes_swapped"
    }
}
