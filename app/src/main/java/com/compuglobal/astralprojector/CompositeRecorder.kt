package com.compuglobal.astralprojector

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.io.File

/**
 * Records both camera [TextureView]s into a single side-by-side MP4 (video-only).
 *
 * Each frame (~30fps), both views are captured via [TextureView.getBitmap] and composited onto
 * the MediaCodec encoder's input [Surface] using [Surface.lockHardwareCanvas] (API 23+, satisfied
 * on Quest 2 which runs Android 12+). The resulting H.264 stream is muxed to an MP4 file.
 *
 * The capture includes whatever the TextureViews currently display — aspect/rotation/flip
 * transforms applied — so the composite matches exactly what the user sees on screen.
 *
 * Audio is intentionally omitted: AUSBC already opens one AudioRecord per USB camera; adding a
 * third concurrent AudioRecord for the composite risks input-device conflicts on Quest hardware.
 * Use the per-camera separate files if audio is needed.
 */
class CompositeRecorder(private val context: Context) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    @Volatile private var muxerStarted = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var outputFile: File? = null

    @Volatile var isRecording = false
        private set

    fun start(outputFile: File, leftView: TextureView, rightView: TextureView): Boolean {
        if (isRecording) return true

        val leftW = leftView.width
        val rightW = rightView.width
        val h = leftView.height
        if (leftW <= 0 || h <= 0) {
            FileLogger.log("composite rec: views not laid out (${leftW}x${h}) — skipping")
            return false
        }
        // MediaCodec requires even dimensions.
        val outW = ((leftW + rightW + 1) / 2) * 2
        val outH = (h + 1) / 2 * 2

        this.outputFile = outputFile
        FileLogger.log("composite rec: starting ${outW}x${outH} -> $outputFile")

        val t = HandlerThread("CompositeRec").also { it.start() }
        thread = t
        val h_ = Handler(t.looper)
        handler = h_

        try {
            val mx = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx

            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = c

            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    videoTrack = mx.addTrack(format)
                    mx.start()
                    muxerStarted = true
                    FileLogger.log("composite rec: muxer started track=$videoTrack")
                }
                override fun onOutputBufferAvailable(
                    codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
                ) {
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        muxerStarted && info.size > 0
                    ) {
                        codec.getOutputBuffer(index)?.let { mx.writeSampleData(videoTrack, it, info) }
                    }
                    codec.releaseOutputBuffer(index, false)
                    // Post finalize AFTER the callback returns to avoid MediaCodec.stop() deadlock.
                    if (isEos) h_.post { finalize() }
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    FileLogger.log("composite rec: codec error", e)
                    isRecording = false
                }
            }, h_)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()

            isRecording = true
            h_.post(buildCaptureRunnable(leftView, rightView, leftW, rightW, h, h_))
        } catch (e: Exception) {
            FileLogger.log("composite rec: start failed", e)
            release()
            discardIfEmpty()
            return false
        }
        return true
    }

    private fun buildCaptureRunnable(
        leftView: TextureView,
        rightView: TextureView,
        leftW: Int,
        rightW: Int,
        h: Int,
        handler: Handler,
    ) = object : Runnable {
        private val intervalMs = 1000L / FRAME_RATE
        override fun run() {
            if (!isRecording) return
            captureFrame(leftView, rightView, leftW, rightW, h)
            handler.postDelayed(this, intervalMs)
        }
    }

    private fun captureFrame(
        leftView: TextureView,
        rightView: TextureView,
        leftW: Int,
        rightW: Int,
        h: Int,
    ) {
        if (!isRecording) return
        val surface = inputSurface ?: return
        val leftBmp = runCatching { leftView.getBitmap(leftW, h) }.getOrNull()
        val rightBmp = runCatching { rightView.getBitmap(rightW, h) }.getOrNull()
        if (leftBmp == null && rightBmp == null) return
        try {
            val canvas = surface.lockHardwareCanvas()
            try {
                if (leftBmp != null) canvas.drawBitmap(leftBmp, 0f, 0f, null)
                if (rightBmp != null) canvas.drawBitmap(rightBmp, leftW.toFloat(), 0f, null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            FileLogger.log("composite rec: canvas lock failed (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            leftBmp?.recycle()
            rightBmp?.recycle()
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        FileLogger.log("composite rec: stopping")
        runCatching { codec?.signalEndOfInputStream() }
            .onFailure {
                FileLogger.log("composite rec: EOS signal failed, forcing finalize", it)
                handler?.post { finalize() }
            }
    }

    private fun finalize() {
        if (codec == null) return  // already finalized
        FileLogger.log("composite rec: finalizing")
        runCatching { codec?.stop() }
        runCatching { if (muxerStarted) muxer?.stop() }
            .onFailure { FileLogger.log("composite rec: muxer stop failed", it) }
        outputFile?.takeIf { it.isFile && it.length() > 0 }?.let { f ->
            android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
            FileLogger.log("composite rec: saved ${f.name} (${f.length()} bytes)")
        }
        release()
        discardIfEmpty()
        outputFile = null
    }

    private fun release() {
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        videoTrack = -1
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun discardIfEmpty() {
        outputFile?.takeIf { it.isFile && it.length() == 0L }?.let {
            FileLogger.log("composite rec: removing empty ${it.name}")
            it.delete()
        }
    }

    companion object {
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 12_000_000
    }
}
