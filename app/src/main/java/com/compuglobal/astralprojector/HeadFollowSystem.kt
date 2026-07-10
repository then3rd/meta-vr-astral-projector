package com.compuglobal.astralprojector

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform

/**
 * ECS system that keeps a panel entity floating a fixed distance in front of the user's head,
 * facing them (billboarded around Y). Runs every frame while [isEnabled] returns true; when
 * disabled it leaves the panel wherever it last was, so the user can pin it in space.
 *
 * The toolkit's own [com.meta.spatial.toolkit.Followable] needs an explicit target entity and
 * didn't track the HMD out of the box, so we read the head pose directly from the local player's
 * avatar body — the same approach the Meta Spatial SDK samples use for head-locked UI.
 *
 * When [isSmoothing] is on, the panel eases toward the target pose each frame instead of snapping
 * to it, so small/quick head movements don't feel rigidly glued to the face.
 *
 * [backOffset] supports curved (cylinder) panels: their entity origin is the cylinder AXIS, with
 * the visible arc sitting `radius` in front of it along the entity's +Z (verified in the SDK's
 * PanelQuadCylinderAnimator bytecode — it shifts the Transform back by the radius so the arc stays
 * put when morphing). We therefore track/smooth the pose of the visible SURFACE (kept [distance]
 * in front of the head) and place the entity [backOffset] metres behind it. Smoothing in surface
 * space matters: the axis can sit tens of metres behind a nearly-flat arc, where independently
 * interpolated position/rotation would swing the arc metres sideways.
 */
class HeadFollowSystem(
    private val panelEntity: () -> com.meta.spatial.core.Entity?,
    private val isEnabled: () -> Boolean,
    private val distance: () -> Float,
    private val isSmoothing: () -> Boolean,
    private val backOffset: () -> Float,
) : SystemBase() {

    // Last pose of the panel's visible surface, used as the interpolation start when smoothing.
    private var currentPose: Pose? = null

    override fun execute() {
        if (!isEnabled()) {
            // Drop the cached pose so re-enabling snaps straight to the head instead of easing
            // in from a stale position.
            currentPose = null
            return
        }
        val entity = panelEntity() ?: return

        val head = systemManager
            .tryFindSystem<PlayerBodyAttachmentSystem>()
            ?.tryGetLocalPlayerAvatarBody()
            ?.head
            ?: return
        val headPose = head.tryGetComponent<Transform>()?.transform ?: return
        // Before tracking initializes, the head sits at the identity pose — ignore it so the panel
        // doesn't snap to the origin.
        if (headPose == Pose()) return

        // A point `distance` metres in front of the head. In the Spatial SDK the head entity's
        // local +Z axis points forward (matching the samples' LookAtHead zOffset default of +1).
        val forward = headPose.q * Vector3(0f, 0f, distance())
        val targetT = headPose.t + forward
        // Face the panel toward the user, but keep it upright (no pitch/roll from looking up/down).
        val targetQ = Quaternion.lookRotationAroundY(forward)

        val prev = currentPose
        val pose = if (isSmoothing() && prev != null) {
            // Ease toward the target: a fixed per-frame fraction gives a light, springy lag.
            Pose(
                prev.t * (1f - SMOOTHING_FACTOR) + targetT * SMOOTHING_FACTOR,
                prev.q.slerp(targetQ, SMOOTHING_FACTOR),
            )
        } else {
            Pose(targetT, targetQ)
        }

        currentPose = pose
        // For a curved panel, step from the surface pose back to the entity origin (cylinder
        // axis). Flat panels have backOffset 0, leaving the pose untouched.
        val entityT = pose.t - (pose.q * Vector3(0f, 0f, backOffset()))
        entity.setComponent(Transform(Pose(entityT, pose.q)))
    }

    private companion object {
        // Fraction of the remaining distance covered each frame (~72 fps on Quest) when smoothing.
        const val SMOOTHING_FACTOR = 0.15f
    }
}
