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
 */
class HeadFollowSystem(
    private val panelEntity: () -> com.meta.spatial.core.Entity?,
    private val isEnabled: () -> Boolean,
    private val distance: () -> Float,
) : SystemBase() {

    override fun execute() {
        if (!isEnabled()) return
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

        // A point `distance` metres straight ahead of where the head is looking.
        val forward = headPose.q * Vector3(0f, 0f, -distance())
        val pose = Pose()
        pose.t = headPose.t + forward
        // Face the panel toward the user, but keep it upright (no pitch/roll from looking up/down).
        pose.q = Quaternion.lookRotationAroundY(forward)
        entity.setComponent(Transform(pose))
    }
}
