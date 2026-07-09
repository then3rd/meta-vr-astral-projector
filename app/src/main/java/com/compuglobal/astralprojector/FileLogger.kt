package com.compuglobal.astralprojector

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only file logger. Writes to every app-specific external dir returned by
 * [Context.getExternalFilesDirs] — that includes the removable SD card (if inserted), which can be
 * read on a PC via a card reader without ADB. Also mirrors to logcat under tag "AstralProjector".
 *
 * Log file: <volume>/Android/data/com.compuglobal.astralprojector/files/camera-eyes.log
 */
object FileLogger {
    private const val TAG = "AstralProjector"
    private const val FILE_NAME = "camera-eyes.log"

    private const val MAX_BUFFER = 500

    private val targets = mutableListOf<File>()
    @Volatile private var initialized = false

    /** In-memory ring buffer so the on-screen overlay can show lines logged before it attached. */
    private val buffer = ArrayDeque<String>()

    /** UI sink for live log lines. Invoked on the logging thread — the sink must marshal to UI. */
    @Volatile private var listener: ((String) -> Unit)? = null

    @Synchronized
    fun setListener(l: ((String) -> Unit)?) {
        listener = l
        if (l != null) buffer.toList().forEach(l)
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

        // Fresh file each launch so we don't read stale sessions.
        synchronized(this) { targets.forEach { runCatching { if (it.exists()) it.delete() } } }

        log("==== session start ====")
        log("device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("log targets: " + targets.joinToString { it.absolutePath })

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("UNCAUGHT EXCEPTION in thread '${t.name}': ${stack(e)}")
            prev?.uncaughtException(t, e)
        }
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
        val line = "${now()} $msg"
        synchronized(this) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
            if (initialized) {
                targets.forEach { f ->
                    runCatching { FileOutputStream(f, true).use { it.write("$line\n".toByteArray()) } }
                }
            }
        }
        listener?.invoke(line)
    }

    fun log(msg: String, e: Throwable) = log("$msg :: ${stack(e)}")

    private fun stack(e: Throwable): String =
        StringWriter().also { sw -> e.printStackTrace(PrintWriter(sw)) }.toString().trim()

    private fun now(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
