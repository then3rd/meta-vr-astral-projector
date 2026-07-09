package com.adiglobal.cameraeyes

import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.MultiCameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
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

    /** Slot 0 = left, slot 1 = right. Null means the slot is free. */
    private val slots = arrayOfNulls<MultiCameraClient.Camera>(SLOT_COUNT)

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
            ICameraStateCallBack.State.OPENED -> hideStatus(idx)
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
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    companion object {
        private const val SLOT_COUNT = 2
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val MAX_LOG_LINES = 400
    }
}
