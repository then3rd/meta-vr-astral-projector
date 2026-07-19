package com.compuglobal.astralprojector

/**
 * Process-wide bridge between the immersive scene ([HeadCursorSystem], running in
 * [ImmersiveActivity]) and the 2D panel UI ([SideBySideCameraFragment], running in
 * [MainActivity]). Both activities share one process, so this `object` is a single shared
 * instance — the same trick [SpatialControls] uses, but for per-frame state that's far too
 * hot to route through SharedPreferences.
 *
 * The head-cursor feature needs data that only the scene knows (the head pose) to drive UI that
 * only the fragment owns (the on-panel buttons + crosshair). The System writes; the fragment
 * reads on its own refresh loop. All fields are [Volatile] so a write on the SDK frame thread is
 * visible to the fragment's main-thread ticker without locking.
 */
object HeadCursorBridge {

    /**
     * True while a head-cursor session is active (the settings menu was opened by a head-shake and
     * the crosshair is live). Written by the fragment (the authority over the session lifecycle),
     * read by [HeadFollowSystem] — which pauses following while it's true so the panel freezes in
     * space and head rotation sweeps the crosshair across it instead of dragging the panel along.
     */
    @Volatile var active: Boolean = false

    /** Cursor position on the panel surface, normalized 0..1 (u = left→right, v = top→bottom). */
    @Volatile var cursorU: Float = 0.5f
    @Volatile var cursorV: Float = 0.5f

    /** True while the head-forward ray actually intersects the panel quad (else the reticle hides). */
    @Volatile var onPanel: Boolean = false

    /**
     * Monotonic counter bumped once per detected head-shake gesture. The fragment remembers the
     * last value it saw; a change means "a shake happened" and toggles the session open/closed.
     * A counter (rather than a boolean flag the fragment must clear) avoids a lost-edge race
     * between the frame thread and the ticker.
     */
    @Volatile var gestureEpoch: Int = 0

    /** Reset transient session state. Called when the fragment view is (re)created. */
    fun reset() {
        active = false
        onPanel = false
        cursorU = 0.5f
        cursorV = 0.5f
    }
}
