package com.compuglobal.astralprojector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Records the headset's passthrough view to an MP4 via the Horizon OS Passthrough Camera API
 * (v74+, Quest 3/3S): the forward passthrough cameras are exposed as regular camera2 devices,
 * gated behind the runtime permission `horizonos.permission.HEADSET_CAMERA`.
 *
 * This is the ONLY way an app can capture passthrough pixels — the compositor excludes
 * passthrough from every screen-capture path (MediaProjection, scrcpy of the app surface) for
 * privacy, so recording the panel surface would show black where the real world is. On Quest 2 or
 * pre-v74 OS the API simply isn't there (camera2 lists no devices / permission unknown); every
 * entry point here degrades to a logged no-op so the USB-camera recordings still proceed.
 *
 * Video-only on purpose: the two AUSBC per-camera recordings each open their own AudioRecord on
 * the headset mic, and a third mic client would raise the odds of one of them being denied input.
 *
 * All camera2 callbacks run on a dedicated HandlerThread; start/stop are called from the main
 * thread. Failures at any stage release everything and log — recording passthrough is best-effort.
 */
class PassthroughRecorder(private val context: Context) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null

    /** True from a successful start() until stop(); the async camera2 bring-up may still fail. */
    var isRecording = false
        private set

    private var outputFile: File? = null

    fun start(outputFile: File): Boolean {
        if (isRecording) return true
        if (ContextCompat.checkSelfPermission(context, MainActivity.HORIZONOS_HEADSET_CAMERA_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            FileLogger.log("passthrough rec: HEADSET_CAMERA/CAMERA permission not granted — skipping (needs Horizon OS v74+, Quest 3/3S)")
            return false
        }
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        val cameraId = pickPassthroughCamera(manager) ?: run {
            FileLogger.log("passthrough rec: no camera2 devices exposed — passthrough API unavailable on this device/OS")
            return false
        }
        val size = pickSize(manager, cameraId)
        FileLogger.log("passthrough rec: starting camera=$cameraId size=$size -> $outputFile")

        this.outputFile = outputFile
        val t = HandlerThread("PassthroughRec").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        try {
            recorder = buildRecorder(outputFile, size)
            // SecurityException here would mean the permission check above raced a revocation.
            manager.openCamera(cameraId, deviceCallback, handler)
        } catch (e: Exception) {
            FileLogger.log("passthrough rec: start failed", e)
            release()
            discardIfEmpty()
            return false
        }
        isRecording = true
        return true
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        FileLogger.log("passthrough rec: stopping")
        // Stop the recorder before tearing down the session so the last buffered frames land.
        runCatching { session?.stopRepeating() }
        runCatching { recorder?.stop() }
            .onFailure { FileLogger.log("passthrough rec: recorder.stop failed (no frames captured?)", it) }
        release()
        // Register with MediaStore so the Files app sees the recording without a reboot.
        outputFile?.takeIf { it.isFile && it.length() > 0 }?.let {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), null, null)
        }
        discardIfEmpty()
        outputFile = null
    }

    /**
     * MediaRecorder.prepare() creates the output file before the async camera2 bring-up, so any
     * later failure (openCamera error, session config failure — the norm on Quest 2, where the
     * passthrough API doesn't exist) leaves a 0-byte .mp4 behind. Remove it.
     */
    private fun discardIfEmpty() {
        outputFile?.takeIf { it.isFile && it.length() == 0L }?.let {
            FileLogger.log("passthrough rec: removing empty ${it.name} (recording never produced frames)")
            it.delete()
        }
    }

    private fun release() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { recorder?.release() }
        recorder = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /**
     * Picks the passthrough camera to record. Horizon OS tags its passthrough devices with vendor
     * keys (com.meta.extra_metadata.camera_source = 0 for passthrough, .position = 0 for left);
     * prefer the left one, but fall back to the first listed id — on Quest, camera2 only lists
     * passthrough cameras anyway (the USB UVC units are not backed by a camera HAL).
     */
    private fun pickPassthroughCamera(manager: CameraManager): String? {
        val ids = runCatching { manager.cameraIdList }.getOrElse { return null }
        if (ids.isEmpty()) return null
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val byName = chars.keys.associateBy { it.name }
            @Suppress("UNCHECKED_CAST")
            fun metaInt(name: String): Int? =
                (byName[name] as? CameraCharacteristics.Key<Any>)?.let { chars.get(it) as? Int }
            val source = metaInt("com.meta.extra_metadata.camera_source")
            val position = metaInt("com.meta.extra_metadata.position")
            FileLogger.log("passthrough rec: camera id=$id source=$source position=$position")
            if (source == 0 && position != 1) return id
        }
        return ids.first()
    }

    /** Largest MediaRecorder-capable output size within the passthrough cameras' native 1280x960. */
    private fun pickSize(manager: CameraManager, cameraId: String): Size {
        val sizes = runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java)?.toList()
        }.getOrNull().orEmpty()
        return sizes.filter { it.width <= 1280 && it.height <= 960 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(1280, 960)
    }

    private fun buildRecorder(outputFile: File, size: Size): MediaRecorder {
        @Suppress("DEPRECATION") // MediaRecorder(Context) needs API 31; minSdk is 24.
        return MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(size.width, size.height)
            setVideoFrameRate(FRAME_RATE)
            setVideoEncodingBitRate(BIT_RATE)
            setOutputFile(outputFile.absolutePath)
            prepare()
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            if (!isRecording) { runCatching { camera.close() }; return }
            val surface = recorder?.surface ?: return
            try {
                @Suppress("DEPRECATION") // SessionConfiguration variant needs API 28; minSdk is 24.
                camera.createCaptureSession(listOf(surface), sessionCallback, handler)
            } catch (e: Exception) {
                FileLogger.log("passthrough rec: createCaptureSession failed", e)
                stop()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            FileLogger.log("passthrough rec: camera disconnected")
            stop()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            FileLogger.log("passthrough rec: camera error $error")
            stop()
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            session = s
            if (!isRecording) return
            try {
                val dev = device ?: return
                val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    recorder?.surface?.let { addTarget(it) }
                }.build()
                s.setRepeatingRequest(request, null, handler)
                recorder?.start()
                FileLogger.log("passthrough rec: recording")
            } catch (e: Exception) {
                FileLogger.log("passthrough rec: session start failed", e)
                stop()
            }
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            FileLogger.log("passthrough rec: session configuration failed")
            stop()
        }
    }

    companion object {
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 8_000_000
    }
}
