package com.compuglobal.astralprojector

import android.content.SharedPreferences
import android.os.Bundle
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.PanelShapeType
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import kotlin.math.max

/**
 * Immersive host activity. [AppSystemActivity] already registers the toolkit systems via its
 * default `registerSystemFeatures()`; here we add [VRFeature] (VR compositor + head-pose tracking)
 * and embed the existing 2D camera UI ([MainActivity]) as a floating panel.
 *
 * - Passthrough is enabled so the real world shows behind the camera panel (mixed reality).
 * - Locomotion (teleport) is disabled so the controller acts as a panel pointer instead.
 * - A [HeadFollowSystem] keeps the panel in front of the user's head when head-follow is on.
 * - The panel's size (scale) and curvature (flat quad <-> cylinder) are user-adjustable and are
 *   applied live by reshaping the panel scene object.
 */
class ImmersiveActivity : AppSystemActivity() {

    private var panelEntity: Entity? = null

    // Read live by HeadFollowSystem every frame; updated by the shared-prefs listener so the
    // in-panel controls (MainActivity) take effect immediately without polling SharedPreferences.
    @Volatile private var headFollowEnabled = true
    @Volatile private var smoothingEnabled = false
    @Volatile private var panelDistance = SpatialControls.DEFAULT_PANEL_DISTANCE

    // Curvature currently baked into the panel mesh; reshape only when it actually changes.
    private var appliedCurve = SpatialControls.DEFAULT_PANEL_CURVE

    // INTERACTION_SDK routes both controller rays AND tracked hands (pinch = click) to panels,
    // so the app is usable without a controller. Requires the oculus.software.handtracking
    // feature + com.oculus.permission.HAND_TRACKING declarations in the manifest.
    override fun registerFeatures(): List<SpatialFeature> = listOf(
        VRFeature(this, inputSystemType = VrInputSystemType.INTERACTION_SDK),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start from a clean, known-good configuration every launch so a persisted setting from a
        // previous session can't reintroduce a config-specific bug. Must run before we read any
        // values below and before the panel/fragment (MainActivity) reads them in onSceneReady.
        SpatialControls.resetToDefaults(this)

        headFollowEnabled = SpatialControls.isHeadFollowEnabled(this)
        smoothingEnabled = SpatialControls.isSmoothingEnabled(this)
        panelDistance = SpatialControls.getPanelDistance(this)

        systemManager.registerSystem(
            HeadFollowSystem(
                panelEntity = { panelEntity },
                isEnabled = { headFollowEnabled },
                distance = { panelDistance },
                isSmoothing = { smoothingEnabled },
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
            width = BASE_WIDTH_M
            height = BASE_HEIGHT_M
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

        // Apply the user's saved scale/curve to the freshly-created panel.
        applyPanelScale()
        applyPanelCurve()

        FileLogger.log(
            "ImmersiveActivity: panel ready distance=${panelDistance}m follow=$headFollowEnabled " +
                "smoothing=$smoothingEnabled passthrough=on"
        )
    }

    /**
     * Panel size via the entity's [Scale] component — the SDK scales the existing mesh and
     * compositor layer in place, so it applies live during a slider drag with no mesh rebuild
     * (rebuilding per progress tick is what made the sliders unresponsive/hard to re-grab).
     */
    private fun applyPanelScale() {
        val entity = panelEntity ?: return
        val scale = SpatialControls.getPanelScale(this)
        entity.setComponent(Scale(Vector3(scale, scale, scale)))
        FileLogger.log("ImmersiveActivity: scale -> $scale")
    }

    /**
     * Curvature interpolates between a flat quad (0) and a cylinder whose angular extent grows
     * with the curve amount (smaller cylinder radius = more wrap-around). This is the one change
     * that genuinely needs [PanelSceneObject.reshape] (mesh rebuild), so the slider only commits
     * it on release, and any failure inside the future is logged instead of silently swallowed.
     */
    private fun applyPanelCurve() {
        val entity = panelEntity ?: return
        val curve = SpatialControls.getPanelCurve(this)
        if (curve == appliedCurve) return

        // Radius from the unscaled width: the Scale component multiplies the whole mesh afterward,
        // which keeps the wrap-around angle constant regardless of panel size.
        val config = if (curve <= CURVE_EPSILON) {
            PanelConfigOptions(
                width = BASE_WIDTH_M,
                height = BASE_HEIGHT_M,
                layoutWidthInPx = PANEL_PX_WIDTH,
                layoutHeightInPx = PANEL_PX_HEIGHT,
                panelShapeType = PanelShapeType.QUAD,
            )
        } else {
            // Angular extent (radians) the panel wraps around the cylinder = width / radius, so
            // radius = width / angle. Larger curve -> larger angle -> smaller radius -> more curved.
            val angle = curve * MAX_CURVE_ANGLE_RAD
            val radius = max(BASE_WIDTH_M / angle, MIN_CURVE_RADIUS_M)
            PanelConfigOptions(
                width = BASE_WIDTH_M,
                height = BASE_HEIGHT_M,
                layoutWidthInPx = PANEL_PX_WIDTH,
                layoutHeightInPx = PANEL_PX_HEIGHT,
                panelShapeType = PanelShapeType.CYLINDER,
                radiusForCylinderOrSphere = radius,
            )
        }

        systemManager.findSystem<SceneObjectSystem>()
            .getSceneObject(entity)
            ?.whenComplete { sceneObject, err ->
                // Exceptions inside CompletableFuture callbacks never surface on their own —
                // log everything so a reshape failure is visible in the on-screen overlay.
                when {
                    err != null ->
                        FileLogger.log("ImmersiveActivity: reshape unavailable: $err")
                    sceneObject !is PanelSceneObject ->
                        FileLogger.log("ImmersiveActivity: reshape skipped, sceneObject=$sceneObject")
                    else -> try {
                        sceneObject.reshape(config)
                        appliedCurve = curve
                        FileLogger.log(
                            "ImmersiveActivity: reshape curve=$curve shape=${config.panelShapeType}"
                        )
                    } catch (t: Throwable) {
                        FileLogger.log("ImmersiveActivity: reshape FAILED: $t")
                    }
                }
            }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SpatialControls.KEY_HEAD_FOLLOW -> {
                headFollowEnabled = SpatialControls.isHeadFollowEnabled(this)
                FileLogger.log("ImmersiveActivity: head-follow -> $headFollowEnabled")
            }
            SpatialControls.KEY_SMOOTHING -> {
                smoothingEnabled = SpatialControls.isSmoothingEnabled(this)
                FileLogger.log("ImmersiveActivity: smoothing -> $smoothingEnabled")
            }
            SpatialControls.KEY_PANEL_SCALE -> runOnMainThread { applyPanelScale() }
            SpatialControls.KEY_PANEL_CURVE -> runOnMainThread { applyPanelCurve() }
        }
    }

    override fun onDestroy() {
        SpatialControls.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        panelEntity?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PANEL_ID = 1
        private const val BASE_WIDTH_M = 2.4f
        private const val BASE_HEIGHT_M = 1.2f
        private const val PANEL_PX_WIDTH = 1920
        private const val PANEL_PX_HEIGHT = 960

        // Curve <= this is treated as flat (quad) to avoid a near-infinite cylinder radius.
        private const val CURVE_EPSILON = 0.02f
        // Max wrap-around at full curve (~150 degrees).
        private const val MAX_CURVE_ANGLE_RAD = 2.618f
        // Clamp so the cylinder can't collapse to an unusably tight tube.
        private const val MIN_CURVE_RADIUS_M = 0.3f
    }
}
