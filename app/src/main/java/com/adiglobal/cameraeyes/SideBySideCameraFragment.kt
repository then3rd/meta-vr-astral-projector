package com.adiglobal.cameraeyes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = inflater.inflate(R.layout.fragment_side_by_side, container, false)
        textures = arrayOf(root.findViewById(R.id.textureLeft), root.findViewById(R.id.textureRight))
        statuses = arrayOf(root.findViewById(R.id.statusLeft), root.findViewById(R.id.statusRight))
        return root
    }

    override fun onCameraAttached(camera: MultiCameraClient.Camera) {
        // Device seen; the base fragment auto-requests USB permission next.
        val idx = firstFreeSlot()
        if (idx >= 0) setStatus(idx, getString(R.string.status_connecting))
    }

    override fun onCameraDetached(camera: MultiCameraClient.Camera) {
        val idx = slotOf(camera)
        if (idx >= 0) {
            slots[idx] = null
            setStatus(idx, getString(R.string.status_waiting))
        }
        camera.closeCamera()
    }

    override fun onCameraConnected(camera: MultiCameraClient.Camera) {
        val idx = firstFreeSlot()
        if (idx < 0) {
            // Only two slots; ignore any additional camera rather than fight over a surface.
            setStatus(SLOT_COUNT - 1, getString(R.string.status_extra))
            camera.closeCamera()
            return
        }
        slots[idx] = camera
        camera.setCameraStateCallBack(this)
        camera.openCamera(textures[idx], buildRequest())
        setStatus(idx, getString(R.string.status_opening))

        // Two identical cameras: after one connects, make sure any other attached-but-unpermitted
        // unit gets its permission prompt (Android shows the USB dialogs one at a time).
        requestPermissionForPendingCameras()
    }

    override fun onCameraDisConnected(camera: MultiCameraClient.Camera) {
        val idx = slotOf(camera)
        if (idx >= 0) {
            slots[idx] = null
            setStatus(idx, getString(R.string.status_disconnected))
        }
        camera.closeCamera()
    }

    override fun onCameraState(
        self: MultiCameraClient.Camera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
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
            if (!hasPermission(device)) {
                requestPermission(device)
                return
            }
        }
    }

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

    companion object {
        private const val SLOT_COUNT = 2
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }
}
