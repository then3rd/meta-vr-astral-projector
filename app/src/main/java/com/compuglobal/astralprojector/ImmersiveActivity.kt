package com.compuglobal.astralprojector

import android.content.Intent
import android.content.SharedPreferences
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Followable
import com.meta.spatial.toolkit.FollowableType
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.vr.VRFeature

/**
 * Immersive host activity. [AppSystemActivity] already registers the toolkit systems (including
 * [com.meta.spatial.toolkit.FollowableSystem]) via its default `registerSystemFeatures()`; here we
 * add [VRFeature], which activates the headset's VR compositor + head-pose tracking. The existing
 * 2D camera UI ([MainActivity]) is embedded as a floating panel that follows the user's head.
 */
class ImmersiveActivity : AppSystemActivity() {

    private var panelEntity: Entity? = null

    // User-facing features layered on top of AppSystemActivity's built-in ToolkitFeature.
    override fun registerFeatures(): List<SpatialFeature> = listOf(
        // VRFeature activates the headset's VR compositor and head-pose tracking.
        VRFeature(this),
    )

    override fun registerPanels(): List<PanelRegistration> = listOf(
        // init block is PanelRegistration.(Entity) -> Unit — `this` = the PanelRegistration.
        PanelRegistration(PANEL_ID) { _: Entity ->
            activityClass = MainActivity::class.java
            panelIntent = Intent(this@ImmersiveActivity, MainActivity::class.java)
        }.config(false) {
            // config block is PanelConfigOptions.() -> Unit — `this` = the options object.
            width = PANEL_WIDTH_M
            height = PANEL_HEIGHT_M
            layoutWidthInPx = PANEL_PX_WIDTH
            layoutHeightInPx = PANEL_PX_HEIGHT
        }
    )

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun onSceneReady() {
        super.onSceneReady()

        val distance = SpatialControls.getPanelDistance(this)
        val headFollowEnabled = SpatialControls.isHeadFollowEnabled(this)

        panelEntity = Entity.createNonNetworked(
            Transform(Pose(Vector3(0f, 0f, -distance), Quaternion(0f, 0f, 0f, 1f))),
            Panel(PANEL_ID),
            Followable(
                offset = Pose(Vector3(0f, 0f, -distance)),
                type = FollowableType.FACE,
                active = headFollowEnabled,
            )
        )

        // The head-follow toggle lives in the panel (MainActivity); it can't call us directly, so
        // it flips a shared preference and we react here. SharedPreferences keeps only a weak
        // reference to the listener, so we hold a strong one in a field.
        SpatialControls.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)

        FileLogger.log("ImmersiveActivity: panel ready distance=${distance}m follow=$headFollowEnabled")
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SpatialControls.KEY_HEAD_FOLLOW) {
            applyHeadFollow(SpatialControls.isHeadFollowEnabled(this))
        }
    }

    /** Re-apply the head-follow state to the panel's [Followable] on the SDK/render thread. */
    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun applyHeadFollow(enabled: Boolean) {
        val distance = SpatialControls.getPanelDistance(this)
        runOnMainThread {
            panelEntity?.setComponent(
                Followable(
                    offset = Pose(Vector3(0f, 0f, -distance)),
                    type = FollowableType.FACE,
                    active = enabled,
                )
            )
            FileLogger.log("ImmersiveActivity: head-follow -> $enabled")
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
