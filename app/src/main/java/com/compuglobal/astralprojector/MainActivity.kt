package com.compuglobal.astralprojector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Single-activity host. All camera work lives in [SideBySideCameraFragment], which extends
 * AUSBC's MultiCameraFragment and owns the USB device lifecycle.
 *
 * The runtime CAMERA permission must be granted BEFORE the fragment starts requesting USB device
 * permission: since Android 10, UsbUserPermissionManager silently denies USB access to
 * video-capture (UVC) devices when the requesting app doesn't hold CAMERA.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Horizon OS (Quest) runtime permission gating USB UVC device access. */
        const val HORIZONOS_USB_CAMERA_PERMISSION = "horizonos.permission.USB_CAMERA"

        /** Horizon OS (v74+) runtime permission gating camera2 access to the passthrough cameras. */
        const val HORIZONOS_HEADSET_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA"

        // adb shell am broadcast -a com.compuglobal.astralprojector.RECORD_TOGGLE -n com.compuglobal.astralprojector/.MainActivity
        const val ACTION_RECORD_TOGGLE = "com.compuglobal.astralprojector.RECORD_TOGGLE"
        const val ACTION_RECORD_START  = "com.compuglobal.astralprojector.RECORD_START"
        const val ACTION_RECORD_STOP   = "com.compuglobal.astralprojector.RECORD_STOP"
        const val ACTION_SETTINGS_OPEN = "com.compuglobal.astralprojector.SETTINGS_OPEN"
        const val ACTION_RESET         = "com.compuglobal.astralprojector.RESET"
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            FileLogger.log("Runtime permission results: $results")
            // Only CAMERA gates startup: the horizonos.* permissions don't exist off Quest, and
            // RECORD_AUDIO / HEADSET_CAMERA merely degrade recording if denied.
            if (results[Manifest.permission.CAMERA] != false) {
                attachCameraFragment()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        FileLogger.log("MainActivity.onNewIntent action=${intent.action}")
        attachCameraFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        FileLogger.log("MainActivity.onCreate (intent action=${intent?.action})")
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            // RECORD_AUDIO (AUSBC's recorder needs the mic track) and HEADSET_CAMERA (passthrough
            // recording) are requested up front alongside the camera permissions, but neither
            // gates startup — denial just limits the Record feature. horizonos.* permissions are
            // skipped where the OS doesn't define them (requesting an unknown permission is a
            // silent permanent denial that would poison the whole dialog on some versions).
            val missing = listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                HORIZONOS_USB_CAMERA_PERMISSION,
                HORIZONOS_HEADSET_CAMERA_PERMISSION,
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.filter {
                !it.startsWith("horizonos.") || packageManager.isPermissionAvailable(it)
            }
            if (missing.isEmpty()) {
                attachCameraFragment()
            } else {
                FileLogger.log("Requesting runtime permissions: $missing")
                cameraPermissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun android.content.pm.PackageManager.isPermissionAvailable(name: String): Boolean =
        try {
            getPermissionInfo(name, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun attachCameraFragment() {
        if (supportFragmentManager.findFragmentById(R.id.container) != null) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, SideBySideCameraFragment())
            .commit()
    }

    private fun cameraFragment(): SideBySideCameraFragment? =
        supportFragmentManager.findFragmentById(R.id.container) as? SideBySideCameraFragment

    /**
     * Route controller/gamepad button presses to the camera fragment so a Quest controller can
     * open the settings menu and navigate it without the pointer. Only ACTION_DOWN is forwarded;
     * anything the fragment doesn't consume falls through to normal handling.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            cameraFragment()?.handleControllerKey(event.keyCode) == true
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Route thumbstick / hat motion to the fragment for menu focus navigation. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (cameraFragment()?.handleControllerMotion(event) == true) return true
        return super.onGenericMotionEvent(event)
    }

    /**
     * In stereo (LeftRight) each eye sees one half of the surface stretched across the full
     * panel, but the SDK's panel input pipeline is stereo-unaware: it maps the ray hit across
     * the FULL surface width (0.13.1 bytecode: PanelShape passes StereoMode only to the
     * compositor layers; PanelInputListener never reads it). A tap therefore lands at twice the
     * horizontal surface position of what the user is aiming at. Halving x sends every pointer
     * event to the left half at the visually-corresponding spot — which is why the duplicated
     * stereo UI treats the left copy as the interactive one.
     */
    private fun remapStereoPointer(ev: MotionEvent) {
        if (SpatialControls.isStereoEnabled(this)) ev.setLocation(ev.x * 0.5f, ev.y)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        remapStereoPointer(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        // Pointer-class only: hover moves from the panel cursor need the same remap as taps,
        // but joystick events carry axis values that must not be rescaled.
        if (ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) remapStereoPointer(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---- adb debug control -------------------------------------------------------

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            FileLogger.log("debugReceiver: action=${intent.action}")
            val frag = cameraFragment() ?: run {
                FileLogger.log("debugReceiver: no fragment attached")
                return
            }
            when (intent.action) {
                ACTION_RECORD_TOGGLE -> frag.toggleRecordingFromAdb()
                ACTION_RECORD_START  -> frag.startRecordingFromAdb()
                ACTION_RECORD_STOP   -> frag.stopRecordingFromAdb()
                ACTION_SETTINGS_OPEN -> frag.openSettingsFromAdb()
                ACTION_RESET         -> frag.resetToDefaultsFromAdb()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACTION_RECORD_TOGGLE)
            addAction(ACTION_RECORD_START)
            addAction(ACTION_RECORD_STOP)
            addAction(ACTION_SETTINGS_OPEN)
            addAction(ACTION_RESET)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(debugReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(debugReceiver)
    }

}
