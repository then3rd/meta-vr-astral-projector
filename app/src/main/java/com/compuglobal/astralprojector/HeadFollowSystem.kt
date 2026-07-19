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

    // Entity pose last written via setComponent, so a converged panel (smoothing settled, head
    // still) stops dirtying the entity's Transform every frame.
    private var lastWrittenPose: Pose? = null
    private var lastEntity: com.meta.spatial.core.Entity? = null

    // The attachment system is registered once for the app's lifetime; cache it instead of a
    // systemManager lookup every frame.
    private var bodySystem: PlayerBodyAttachmentSystem? = null

    override fun execute() {
        if (!isEnabled()) {
            // Drop the cached poses so re-enabling snaps straight to the head instead of easing
            // in from a stale position (and always re-writes after e.g. a curve morph moved us).
            currentPose = null
            lastWrittenPose = null
            return
        }
        val entity = panelEntity() ?: return
        if (entity !== lastEntity) {
            // Fresh entity (e.g. stereo recreation) spawns at its own pose — force a write.
            lastEntity = entity
            lastWrittenPose = null
        }

        val system = bodySystem
            ?: systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.also { bodySystem = it }
        val head = system
            ?.tryGetLocalPlayerAvatarBody()
            ?.head
            ?: return
        val headPose = head.tryGetComponent<Transform>()?.transform ?: return
        // Before tracking initializes, the head sits at the identity pose — ignore it so the panel
        // doesn't snap to the origin.
        if (headPose == IDENTITY_POSE) return

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

        // Skip the ECS write when the panel is already (sub-millimetre / sub-0.1°) at the target:
        // with smoothing on the pose converges and, head still, would otherwise dirty the panel
        // entity's Transform on every one of ~72 frames/s indefinitely.
        val last = lastWrittenPose
        if (last != null && distanceSq(entityT, last.t) < POS_EPS_SQ && rotationClose(pose.q, last.q)) {
            return
        }
        lastWrittenPose = Pose(entityT, pose.q)
        entity.setComponent(Transform(Pose(entityT, pose.q)))
    }

    private fun distanceSq(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }

    /** True when the quaternions differ by a negligible rotation (|dot| ~ 1). */
    private fun rotationClose(a: Quaternion, b: Quaternion): Boolean {
        val dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
        return dot > ROT_DOT_MIN || dot < -ROT_DOT_MIN
    }

    private companion object {
        // Fraction of the remaining distance covered each frame (~72 fps on Quest) when smoothing.
        const val SMOOTHING_FACTOR = 0.15f

        val IDENTITY_POSE = Pose()

        // Convergence thresholds for skipping the per-frame Transform write: 1 mm position,
        // cos(half-angle) for ~0.1 degrees rotation.
        const val POS_EPS_SQ = 1e-6f
        const val ROT_DOT_MIN = 0.9999996f
    }
}
