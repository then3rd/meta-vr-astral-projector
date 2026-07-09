package com.adiglobal.cameraeyes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
}
