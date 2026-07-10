package com.compuglobal.astralprojector

import android.Manifest
import android.content.pm.PackageManager
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
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            FileLogger.log("Runtime permission results: $results")
            // USB_CAMERA only exists on Horizon OS; on regular Android its denial is expected,
            // so only CAMERA gates startup.
            if (results[Manifest.permission.CAMERA] == true) {
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
            val missing = listOf(Manifest.permission.CAMERA, HORIZONOS_USB_CAMERA_PERMISSION)
                .filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
            if (missing.isEmpty() ||
                missing.singleOrNull() == HORIZONOS_USB_CAMERA_PERMISSION && !isHorizonOs()
            ) {
                attachCameraFragment()
            } else {
                FileLogger.log("Requesting runtime permissions: $missing")
                cameraPermissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun isHorizonOs(): Boolean =
        packageManager.isPermissionAvailable(HORIZONOS_USB_CAMERA_PERMISSION)

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
}
