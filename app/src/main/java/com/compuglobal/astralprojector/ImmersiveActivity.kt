package com.compuglobal.astralprojector

import android.content.SharedPreferences
import android.os.Bundle
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType

/**
 * Immersive host activity. [AppSystemActivity] already registers the toolkit systems via its
 * default `registerSystemFeatures()`; here we add [VRFeature] (VR compositor + head-pose tracking)
 * and embed the existing 2D camera UI ([MainActivity]) as a floating panel.
 *
 * - Passthrough is enabled so the real world shows behind the camera panel (mixed reality).
 * - Locomotion (teleport) is disabled so the controller acts as a panel pointer instead.
 * - A [HeadFollowSystem] keeps the panel in front of the user's head when head-follow is on.
 */
class ImmersiveActivity : AppSystemActivity() {

    private var panelEntity: Entity? = null

    // Read live by HeadFollowSystem every frame; updated by the shared-prefs listener so the
    // in-panel toggle (MainActivity) takes effect immediately without polling SharedPreferences.
    @Volatile private var headFollowEnabled = true
    @Volatile private var panelDistance = SpatialControls.DEFAULT_PANEL_DISTANCE

    // SIMPLE_CONTROLLER gives a controller ray pointer for interacting with panels.
    override fun registerFeatures(): List<SpatialFeature> = listOf(
        VRFeature(this, inputSystemType = VrInputSystemType.SIMPLE_CONTROLLER),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headFollowEnabled = SpatialControls.isHeadFollowEnabled(this)
        panelDistance = SpatialControls.getPanelDistance(this)

        systemManager.registerSystem(
            HeadFollowSystem(
                panelEntity = { panelEntity },
                isEnabled = { headFollowEnabled },
                distance = { panelDistance },
            )
        )

        // SharedPreferences keeps only a weak reference to the listener, so hold a strong field ref.
        SpatialControls.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun registerPanels(): List<PanelRegistration> = listOf(
        // init block is PanelRegistration.(Entity) -> Unit — `this` = the PanelRegistration.
        // Only ONE panel source may be set; activityClass hosts MainActivity as a 2D panel.
        PanelRegistration(PANEL_ID) { _: Entity ->
            activityClass = MainActivity::class.java
        }.config {
            // config block is PanelConfigOptions.() -> Unit — `this` = the options object.
            // Use the default (append) ordering so these explicit dimensions win over the SDK's
            // default sizing (which would otherwise shrink the panel via fractionOfScreen).
            width = PANEL_WIDTH_M
            height = PANEL_HEIGHT_M
            layoutWidthInPx = PANEL_PX_WIDTH
            layoutHeightInPx = PANEL_PX_HEIGHT
        }
    )

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun onSceneReady() {
        super.onSceneReady()

        // Mixed-reality passthrough: show the real world behind the panel.
        scene.enablePassthrough(true)
        // LOCAL reference space places the panel relative to the user and recenters with them.
        scene.setReferenceSpace(ReferenceSpace.LOCAL)

        // Disable teleport locomotion; with it active the controller shows a teleport arc instead
        // of a panel pointer, so buttons can't be clicked.
        systemManager.tryFindSystem<LocomotionSystem>()?.enableLocomotion(false)

        panelEntity = Entity.createNonNetworked(
            Transform(Pose(Vector3(0f, 0f, -panelDistance), Quaternion(0f, 0f, 0f, 1f))),
            Panel(PANEL_ID),
        )

        FileLogger.log(
            "ImmersiveActivity: panel ready distance=${panelDistance}m follow=$headFollowEnabled passthrough=on"
        )
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SpatialControls.KEY_HEAD_FOLLOW) {
            headFollowEnabled = SpatialControls.isHeadFollowEnabled(this)
            FileLogger.log("ImmersiveActivity: head-follow -> $headFollowEnabled")
        }
    }

    override fun onDestroy() {
        SpatialControls.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        panelEntity?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PANEL_ID = 1
        private const val PANEL_WIDTH_M = 2.4f
        private const val PANEL_HEIGHT_M = 1.2f
        private const val PANEL_PX_WIDTH = 1920
        private const val PANEL_PX_HEIGHT = 960
    }
}
