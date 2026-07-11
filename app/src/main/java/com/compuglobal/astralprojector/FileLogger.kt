package com.compuglobal.astralprojector

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug-only file logger. Writes to every app-specific external dir returned by
 * [Context.getExternalFilesDirs] — that includes the removable SD card (if inserted), which can be
 * read on a PC via a card reader without ADB. Also mirrors to logcat under tag "AstralProjector".
 *
 * All file I/O runs on a dedicated background thread: callers (the main thread, AUSBC camera
 * callbacks) never block on external-storage (FUSE) writes, which can take tens of milliseconds
 * per open/write/close. Writers are opened once per session and kept open, flushed per line.
 *
 * Log file: <volume>/Android/data/com.compuglobal.astralprojector/files/camera-eyes.log
 */
object FileLogger {
    private const val TAG = "AstralProjector"
    private const val FILE_NAME = "camera-eyes.log"

    private const val MAX_BUFFER = 500

    private val targets = mutableListOf<File>()

    /** Open writers, one per target file. Touched only on [writeHandler]'s thread. */
    private val writers = mutableListOf<BufferedWriter>()

    @Volatile private var initialized = false

    /** In-memory ring buffer so the on-screen overlay can show lines logged before it attached. */
    private val buffer = ArrayDeque<String>()

    /** UI sink for live log lines. Invoked on the logging thread — the sink must marshal to UI. */
    @Volatile private var listener: ((String) -> Unit)? = null

    /** Owns all file I/O so log() never blocks its caller on storage. */
    private val writeHandler = Handler(HandlerThread("FileLogger").apply { start() }.looper)

    /** SimpleDateFormat is not thread-safe and log() is called from arbitrary threads. */
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Synchronized
    fun setListener(l: ((String) -> Unit)?) {
        listener = l
        if (l != null) synchronized(buffer) { buffer.toList() }.forEach(l)
    }

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        // All external volumes' app dirs: [0] is primary/internal-emulated, [1+] removable (SD).
        app.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            runCatching { dir.mkdirs(); targets.add(File(dir, FILE_NAME)) }
        }
        if (targets.isEmpty()) {
            runCatching { targets.add(File(app.filesDir, FILE_NAME)) }
        }
        initialized = true

        // Fresh file each launch so we don't read stale sessions. Runs first on the write thread,
        // so writers exist before any line posted by the log() calls below.
        writeHandler.post {
            targets.forEach { f ->
                runCatching {
                    if (f.exists()) f.delete()
                    writers.add(BufferedWriter(FileWriter(f, true)))
                }
            }
        }

        log("==== session start ====")
        log("device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("log targets: " + targets.joinToString { it.absolutePath })

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("UNCAUGHT EXCEPTION in thread '${t.name}': ${stack(e)}")
            // The process is about to die; give the write thread a moment to drain to disk.
            awaitDrain(1000)
            prev?.uncaughtException(t, e)
        }
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
        val line = "${fmt.get()!!.format(Date())} $msg"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        if (initialized) {
            writeHandler.post {
                writers.forEach { w ->
                    runCatching { w.write(line); w.newLine(); w.flush() }
                }
            }
        }
        listener?.invoke(line)
    }

    fun log(msg: String, e: Throwable) = log("$msg :: ${stack(e)}")

    /** Blocks until every line posted so far has been written (crash path only). */
    private fun awaitDrain(timeoutMs: Long) {
        val latch = CountDownLatch(1)
        writeHandler.post { latch.countDown() }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun stack(e: Throwable): String =
        StringWriter().also { sw -> e.printStackTrace(PrintWriter(sw)) }.toString().trim()
}
