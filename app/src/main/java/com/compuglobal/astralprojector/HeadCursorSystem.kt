package com.compuglobal.astralprojector

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import kotlin.math.abs
import kotlin.math.atan2

/**
 * ECS system powering the head-tracked menu cursor. Runs every frame (like [HeadFollowSystem]) and
 * does two independent jobs, both writing to [HeadCursorBridge] for the fragment to consume:
 *
 * 1. **Shake gesture** — watches head yaw for a left-right-left-right "no" shake and bumps
 *    [HeadCursorBridge.gestureEpoch] when it sees enough reversals in a short window. The fragment
 *    treats each bump as a toggle of the head-cursor menu.
 *
 * 2. **Cursor projection** — casts the head-forward ray onto the (frozen, while a session is
 *    active) panel quad by plane intersection and writes the hit point as a normalized (u, v) on
 *    the panel surface. Because the ray is the gaze center, the resulting point renders at the
 *    centre of the user's view: the crosshair sits still in the HMD while the panel's buttons slide
 *    under it as the head turns.
 *
 * The head pose is read exactly as [HeadFollowSystem] reads it — the local player avatar body's
 * head [Transform] — so the two systems agree on "forward" (the head entity's local +Z).
 */
class HeadCursorSystem(
    private val panelEntity: () -> Entity?,
    /** Panel half-width and half-height in metres (already scaled), for the plane→uv mapping. */
    private val panelHalfExtents: () -> Pair<Float, Float>,
) : SystemBase() {

    // Cached once (registered for the app's lifetime), like HeadFollowSystem does.
    private var bodySystem: PlayerBodyAttachmentSystem? = null

    // --- shake-detection state ---
    private var prevYaw: Float? = null
    private var swingDir = 0            // +1 / -1 = current horizontal swing direction, 0 = unknown
    private var swingRefYaw = 0f        // yaw at the start of the current swing
    private var swingExtreme = 0f       // furthest yaw reached during the current swing
    private val reversalTimes = ArrayDeque<Long>()
    private var cooldownUntil = 0L      // ignore new gestures until this wall-clock time

    override fun execute() {
        val headPose = headPose() ?: return
        val now = System.currentTimeMillis()
        detectShake(headPose, now)
        updateCursor(headPose)
    }

    private fun headPose(): Pose? {
        val system = bodySystem
            ?: systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.also { bodySystem = it }
        val head = system?.tryGetLocalPlayerAvatarBody()?.head ?: return null
        val pose = head.tryGetComponent<Transform>()?.transform ?: return null
        // Before tracking initializes the head sits at the identity pose — ignore it.
        if (pose == IDENTITY_POSE) return null
        return pose
    }

    /**
     * Counts horizontal head reversals. A "reversal" is a change in yaw direction whose preceding
     * swing exceeded [YAW_SWING_MIN_RAD] (so slow drift and micro-jitter don't count). Enough
     * reversals inside [SHAKE_WINDOW_MS] fire the gesture, then a cooldown suppresses the natural
     * head-settling motion from immediately re-triggering.
     */
    private fun detectShake(headPose: Pose, now: Long) {
        // Head-forward projected onto the horizontal plane; yaw is its bearing. A fresh Vector3 is
        // constructed per multiply (the SDK's vector operators may write in place — HeadFollowSystem
        // follows the same rule), so no shared constant is ever handed to `q * v`.
        val fwd = headPose.q * Vector3(0f, 0f, 1f)
        val yaw = atan2(fwd.x, fwd.z)

        val prev = prevYaw
        prevYaw = yaw
        if (prev == null) {
            swingRefYaw = yaw
            swingExtreme = yaw
            return
        }

        val d = yaw - prev
        val dir = when {
            d > NOISE_EPS_RAD -> 1
            d < -NOISE_EPS_RAD -> -1
            else -> return // essentially still this frame; keep the current swing going
        }

        if (swingDir == 0) {
            swingDir = dir
            swingRefYaw = prev
            swingExtreme = yaw
            return
        }

        if (dir == swingDir) {
            swingExtreme = yaw
            return
        }

        // Direction flipped: close out the swing that just ended.
        val amplitude = abs(swingExtreme - swingRefYaw)
        if (amplitude >= YAW_SWING_MIN_RAD) {
            reversalTimes.addLast(now)
            while (reversalTimes.isNotEmpty() && now - reversalTimes.first() > SHAKE_WINDOW_MS) {
                reversalTimes.removeFirst()
            }
            if (now >= cooldownUntil && reversalTimes.size >= SHAKE_MIN_REVERSALS) {
                HeadCursorBridge.gestureEpoch++
                FileLogger.log("head-cursor: shake detected (reversals=${reversalTimes.size}) -> toggle")
                reversalTimes.clear()
                cooldownUntil = now + SHAKE_COOLDOWN_MS
            }
        }
        // Start the next swing from the turning point.
        swingRefYaw = swingExtreme
        swingExtreme = yaw
        swingDir = dir
    }

    /**
     * Intersects the head-forward ray with the panel plane and writes the hit as normalized panel
     * coordinates. Works for the flat quad (mono and stereo are both flat while the menu is up);
     * a curved panel isn't a concern because the cursor menu is only used with follow paused and
     * the panel is flat in the modes this ships for.
     */
    private fun updateCursor(headPose: Pose) {
        val entity = panelEntity() ?: run { HeadCursorBridge.onPanel = false; return }
        val panel = entity.tryGetComponent<Transform>()?.transform
            ?: run { HeadCursorBridge.onPanel = false; return }
        val (halfW, halfH) = panelHalfExtents()
        if (halfW <= 0f || halfH <= 0f) { HeadCursorBridge.onPanel = false; return }

        val origin = headPose.t
        val fwd = headPose.q * Vector3(0f, 0f, 1f)
        val normal = panel.q * Vector3(0f, 0f, 1f)
        val denom = dot(fwd, normal)
        if (abs(denom) < PARALLEL_EPS) { HeadCursorBridge.onPanel = false; return }

        val tHit = dot(panel.t - origin, normal) / denom
        if (tHit <= 0f) { HeadCursorBridge.onPanel = false; return } // panel is behind the head

        val hit = origin + fwd * tHit
        val rel = hit - panel.t
        // Panel-local axes: +X = surface right, +Y = surface up (standard panel texture mapping).
        val x = dot(rel, panel.q * Vector3(1f, 0f, 0f))
        val y = dot(rel, panel.q * Vector3(0f, 1f, 0f))

        // If the crosshair moves opposite to your head on-device, flip U_SIGN / V_SIGN below.
        val u = 0.5f + U_SIGN * x / (2f * halfW)
        val v = 0.5f + V_SIGN * y / (2f * halfH) // V_SIGN = -1: panel +Y is up, screen v grows down

        HeadCursorBridge.onPanel =
            u >= -EDGE_MARGIN && u <= 1f + EDGE_MARGIN && v >= -EDGE_MARGIN && v <= 1f + EDGE_MARGIN
        HeadCursorBridge.cursorU = u.coerceIn(0f, 1f)
        HeadCursorBridge.cursorV = v.coerceIn(0f, 1f)
    }

    private fun dot(a: Vector3, b: Vector3): Float = a.x * b.x + a.y * b.y + a.z * b.z

    private companion object {
        val IDENTITY_POSE = Pose()

        // Panel texture axis signs (calibration): +1 assumes panel local +X = screen right and
        // +Y = screen up. V maps panel-up to screen-down, hence the negative.
        const val U_SIGN = 1f
        const val V_SIGN = -1f

        // A ray nearly parallel to the panel never yields a usable hit.
        const val PARALLEL_EPS = 1e-4f
        // Allow the cursor a hair past the physical edge before it counts as "off panel".
        const val EDGE_MARGIN = 0.02f

        // --- shake tuning ---
        // Per-frame yaw change below this is treated as no motion (rejects sensor jitter).
        const val NOISE_EPS_RAD = 0.003f
        // A single swing must exceed this (~8°) to count as a deliberate turn, not drift.
        const val YAW_SWING_MIN_RAD = 0.14f
        // Reversals must all fall within this window to add up to a shake.
        const val SHAKE_WINDOW_MS = 1500L
        // Number of direction reversals (L→R→L→R…) that constitute the gesture. Four ≈ a firm
        // three-shake "no"; well above the one/two reversals a normal glance produces.
        const val SHAKE_MIN_REVERSALS = 4
        // Suppress re-triggering for this long after a gesture fires (covers head-settling).
        const val SHAKE_COOLDOWN_MS = 1500L
    }
}
