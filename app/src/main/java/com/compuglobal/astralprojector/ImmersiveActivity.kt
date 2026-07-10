package com.compuglobal.astralprojector

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.meta.spatial.animation.PanelAnimationFeature
import com.meta.spatial.animation.PanelQuadCylinderAnimation
import com.meta.spatial.animation.PanelQuadCylinderAnimationType
import com.meta.spatial.core.DataModel
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Scale
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

    private val mainHandler = Handler(Looper.getMainLooper())

    // Read live by HeadFollowSystem every frame; updated by the shared-prefs listener so the
    // in-panel controls (MainActivity) take effect immediately without polling SharedPreferences.
    @Volatile private var headFollowEnabled = true
    @Volatile private var smoothingEnabled = false
    @Volatile private var panelDistance = SpatialControls.DEFAULT_PANEL_DISTANCE

    // Curvature currently applied to the panel; used to pick the right morph animation type
    // and to skip redundant re-applies.
    private var appliedCurve = SpatialControls.DEFAULT_PANEL_CURVE

    // Cylinder radius currently applied to the panel mesh (0 = flat quad). A curved panel's
    // entity origin is the cylinder AXIS with the visible arc `radius` in front of it, so
    // HeadFollowSystem places the entity backOffset = curveRadius * panelScale behind the
    // surface pose (the Scale component scales the mesh, and with it the world-space offset).
    @Volatile private var curveRadius = 0f

    // Scale currently applied to the panel entity; feeds the backOffset above.
    @Volatile private var panelScale = SpatialControls.DEFAULT_PANEL_SCALE

    // DataModel time (ms, same clock the animator uses) until which head-follow stays suspended:
    // during a quad<->cylinder morph the SDK's PanelQuadCylinderAnimator owns the panel Transform
    // (it offsets the entity incrementally as the radius animates), and a per-frame follow would
    // fight those increments. Once the morph completes the animator never touches the Transform
    // again (verified in the AAR bytecode), so follow can resume with the backOffset compensation.
    @Volatile private var followSuspendedUntil = 0L

    // INTERACTION_SDK routes both controller rays AND tracked hands (pinch = click) to panels,
    // so the app is usable without a controller. Requires the oculus.software.handtracking
    // feature + com.oculus.permission.HAND_TRACKING declarations in the manifest.
    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun registerFeatures(): List<SpatialFeature> = listOf(
        VRFeature(this, inputSystemType = VrInputSystemType.INTERACTION_SDK),
        // Registers the PanelQuadCylinderAnimation component + system used by applyPanelCurve.
        PanelAnimationFeature(),
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
                // Paused only while a curve morph animation is running — see followSuspendedUntil.
                isEnabled = {
                    headFollowEnabled &&
                        DataModel.getLocalDataModelTime() >= followSuspendedUntil
                },
                distance = { panelDistance },
                isSmoothing = { smoothingEnabled },
                backOffset = { curveRadius * panelScale },
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
            // Re-evaluated on every panel (re)creation: config modifiers are re-applied each time
            // panelCreator runs, so recreatePanel() picks up the current stereo preference here.
            val stereo = SpatialControls.isStereoEnabled(this@ImmersiveActivity)
            // In stereo the compositor shows only one half of the surface to each eye, so the
            // quad's physical size matches the PER-EYE aspect (960x1160 px half -> ~1.2x1.45 m).
            width = if (stereo) STEREO_WIDTH_M else BASE_WIDTH_M
            height = BASE_HEIGHT_M
            layoutWidthInPx = PANEL_PX_WIDTH
            layoutHeightInPx = PANEL_PX_HEIGHT
            // Left half of the surface -> left eye, right half -> right eye: the fragment's
            // side-by-side panes become a true binocular view.
            stereoMode = if (stereo) StereoMode.LeftRight else StereoMode.None
            // Alpha-blend the panel against the scene so transparent UI pixels (the settings
            // strip and the gap between the panes) show passthrough instead of opaque black.
            // Requires the hosted activity's window/views to actually be transparent too
            // (Theme.AstralProjector + activity_main have no opaque background).
            // Also on in stereo so the per-eye settings float over passthrough like the mono
            // menu: the scene-texture path this forces does support per-eye sampling
            // (SceneMaterial.setStereoMode / nativeSetStereoParams in the 0.13.1 bytecode).
            enableTransparent = true
        }
    )

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun onSceneReady() {
        super.onSceneReady()

        // Mixed-reality passthrough: show the real world behind the panel (unless disabled).
        scene.enablePassthrough(SpatialControls.isPassthroughEnabled(this))
        // LOCAL reference space places the panel relative to the user and recenters with them.
        scene.setReferenceSpace(ReferenceSpace.LOCAL)

        // Disable teleport locomotion; with it active the controller shows a teleport arc instead
        // of a panel pointer, so buttons can't be clicked.
        systemManager.tryFindSystem<LocomotionSystem>()?.enableLocomotion(false)

        createPanelEntity()

        FileLogger.log(
            "ImmersiveActivity: panel ready distance=${panelDistance}m follow=$headFollowEnabled " +
                "smoothing=$smoothingEnabled passthrough=on"
        )
    }

    /** Creates the (single) panel entity and applies the user's saved scale/curve to it. */
    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun createPanelEntity() {
        // A fresh panel mesh is always a flat quad — reset the curve tracking so applyPanelCurve
        // re-applies a saved curve instead of skipping it as already-applied (and so head-follow's
        // backOffset doesn't compensate for a curvature the new mesh doesn't have).
        appliedCurve = SpatialControls.DEFAULT_PANEL_CURVE
        curveRadius = 0f

        panelEntity = Entity.createNonNetworked(
            Transform(Pose(Vector3(0f, 0f, -panelDistance), Quaternion(0f, 0f, 0f, 1f))),
            Panel(PANEL_ID),
        )
        applyPanelScale()
        applyPanelCurve()
    }

    /**
     * Destroys and recreates the panel entity so the registration's config block re-runs with the
     * current preferences. Needed for stereo: PanelSceneObject has no stereo-mode setter, so the
     * only way to switch is a fresh scene object. Destroying the panel also tears down its virtual
     * display (finishing MainActivity); the new panel relaunches it, and the cameras reconnect via
     * the same attach/permission flow used on a physical replug. The delay lets the click that
     * triggered the toggle finish dispatching inside the dying panel first.
     */
    private fun recreatePanel() {
        panelEntity?.destroy()
        panelEntity = null
        mainHandler.postDelayed({ createPanelEntity() }, PANEL_RECREATE_DELAY_MS)
    }

    /**
     * Panel size via the entity's [Scale] component — the SDK scales the existing mesh and
     * compositor layer in place, so it applies live during a slider drag with no mesh rebuild
     * (rebuilding per progress tick is what made the sliders unresponsive/hard to re-grab).
     */
    private fun applyPanelScale() {
        val entity = panelEntity ?: return
        val scale = SpatialControls.getPanelScale(this)
        panelScale = scale
        entity.setComponent(Scale(Vector3(scale, scale, scale)))
        FileLogger.log("ImmersiveActivity: scale -> $scale")
    }

    /**
     * Curvature interpolates between a flat quad (0) and a cylinder whose angular extent grows
     * with the curve amount (smaller cylinder radius = more wrap-around). Applied via the SDK's
     * [PanelQuadCylinderAnimation]: its system performs the mesh reshape each animation frame AND
     * offsets the panel Transform by the radius delta — the cylinder mesh is built around the
     * cylinder axis, so without that offset the visible arc drifts away from the user (this is
     * exactly the bug our hand-rolled reshape had).
     */
    private fun applyPanelCurve() {
        val entity = panelEntity ?: return
        // Stereo is flat-quad only: the cylinder morph would bend each eye's half around the
        // full panel's axis, breaking the per-eye geometry. The stereo toggle zeroes the curve
        // pref before recreating the panel; this guard keeps any later write from curving it.
        if (SpatialControls.isStereoEnabled(this)) return
        val curve = SpatialControls.getPanelCurve(this)
        if (curve == appliedCurve) return
        appliedCurve = curve

        // Angular extent (radians) the panel wraps around the cylinder = width / radius, so
        // radius = width / angle: larger curve -> larger angle -> smaller radius -> more curved.
        val meshRadius = if (curve <= CURVE_EPSILON) 0f
        else max(BASE_WIDTH_M / (curve * MAX_CURVE_ANGLE_RAD), MIN_CURVE_RADIUS_M)

        val oldRadius = curveRadius
        if (meshRadius == oldRadius) return
        curveRadius = meshRadius

        val startTime = DataModel.getLocalDataModelTime()
        // Hand the Transform to the animator for the morph (plus a margin: the animator does its
        // completion work on the first frame past the duration), then follow takes back over.
        followSuspendedUntil = startTime + CURVE_ANIM_MS + CURVE_ANIM_MARGIN_MS

        val type = when {
            oldRadius == 0f -> PanelQuadCylinderAnimationType.QUAD_TO_CYLINDER
            meshRadius == 0f -> PanelQuadCylinderAnimationType.CYLINDER_TO_QUAD
            else -> PanelQuadCylinderAnimationType.CYLINDER_TO_CYLINDER
        }
        entity.setComponent(
            PanelQuadCylinderAnimation(
                animationType = type,
                targetRadius = meshRadius,
                startTime = startTime,
                durationInMs = CURVE_ANIM_MS,
            )
        )
        FileLogger.log(
            "ImmersiveActivity: curve=$curve -> $type radius=$meshRadius " +
                "(head-follow paused for morph, resumes at $followSuspendedUntil)"
        )
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
            SpatialControls.KEY_PASSTHROUGH -> runOnMainThread {
                val on = SpatialControls.isPassthroughEnabled(this)
                scene.enablePassthrough(on)
                FileLogger.log("ImmersiveActivity: passthrough -> $on")
            }
            SpatialControls.KEY_STEREO -> runOnMainThread {
                val on = SpatialControls.isStereoEnabled(this)
                if (on) SpatialControls.setPanelCurve(this, 0f)
                recreatePanel()
                FileLogger.log("ImmersiveActivity: stereo -> $on (panel recreated)")
            }
        }
    }

    override fun onDestroy() {
        SpatialControls.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        mainHandler.removeCallbacksAndMessages(null)
        panelEntity?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PANEL_ID = 1
        // Panel is taller than the video (2.4x1.2 / 1920x960) by an extra strip that hosts the
        // always-visible settings bar over passthrough. Height grown proportionally so the video
        // region keeps its ~square-per-pane geometry and pixels stay square (px/m constant).
        private const val BASE_WIDTH_M = 2.4f
        private const val BASE_HEIGHT_M = 1.45f
        private const val PANEL_PX_WIDTH = 1920
        private const val PANEL_PX_HEIGHT = 1160
        // Stereo quad width: each eye sees one 960x1160 half of the surface, so the quad keeps
        // the per-eye pixel aspect at the same px/m density (1.45 * 960/1160 ≈ 1.2).
        private const val STEREO_WIDTH_M = 1.2f
        // Grace period between destroying the old panel and creating the new one, so the toggle
        // click finishes dispatching inside the dying panel's virtual display first.
        private const val PANEL_RECREATE_DELAY_MS = 150L

        // Quad<->cylinder morph duration. Short: each animation frame rebuilds the panel mesh.
        private const val CURVE_ANIM_MS = 300L
        // Extra head-follow suspension past the morph end: the animator finalizes (cylinder->quad
        // reshape + transform restore) on its first frame AFTER the duration elapses.
        private const val CURVE_ANIM_MARGIN_MS = 100L

        // Curve <= this is treated as flat (quad) to avoid a near-infinite cylinder radius.
        private const val CURVE_EPSILON = 0.02f
        // Max wrap-around at full curve (~150 degrees).
        private const val MAX_CURVE_ANGLE_RAD = 2.618f
        // Clamp so the cylinder can't collapse to an unusably tight tube.
        private const val MIN_CURVE_RADIUS_M = 0.3f
    }
}
