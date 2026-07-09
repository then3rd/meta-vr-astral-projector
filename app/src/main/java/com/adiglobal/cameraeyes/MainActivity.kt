package com.adiglobal.cameraeyes

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Single-activity host. All camera work lives in [SideBySideCameraFragment], which extends
 * AUSBC's MultiCameraFragment and owns the USB device lifecycle.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SideBySideCameraFragment())
                .commit()
        }
    }
}
