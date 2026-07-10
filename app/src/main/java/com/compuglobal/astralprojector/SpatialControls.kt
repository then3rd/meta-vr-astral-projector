package com.compuglobal.astralprojector

import android.content.Context
import android.content.SharedPreferences

object SpatialControls {
    private const val PREFS_NAME = "spatial_controls"

    /** Keys are public so change listeners can filter callbacks. */
    const val KEY_HEAD_FOLLOW = "head_follow_enabled"
    const val KEY_SMOOTHING = "head_follow_smoothing"
    const val KEY_PANEL_SCALE = "panel_scale"
    const val KEY_PANEL_CURVE = "panel_curve"
    private const val KEY_PANEL_DISTANCE = "panel_distance_m"

    const val DEFAULT_PANEL_DISTANCE = 2.0f
    private const val MIN_PANEL_DISTANCE = 0.5f
    private const val MAX_PANEL_DISTANCE = 5.0f

    /** Panel size multiplier applied to the base panel dimensions. */
    const val DEFAULT_PANEL_SCALE = 1.0f
    const val MIN_PANEL_SCALE = 0.5f
    const val MAX_PANEL_SCALE = 2.5f

    /** Curvature amount: 0 = flat (quad), 1 = maximally curved (cylinder). */
    const val DEFAULT_PANEL_CURVE = 0.0f
    const val MIN_PANEL_CURVE = 0.0f
    const val MAX_PANEL_CURVE = 1.0f

    /**
     * The single app-wide preferences instance. Both [MainActivity] (running as the panel) and
     * [ImmersiveActivity] share this process, so Android returns the same object here — change
     * listeners registered by one activity fire for edits made by the other.
     */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Wipe all persisted immersive settings back to their defaults. Called once at process
     * startup (from [ImmersiveActivity.onCreate]) so the app always begins in a known-good
     * configuration — persisted head-follow / smoothing / scale / curve values from a previous
     * session can't carry over and reintroduce a config-specific bug. In-session changes still
     * apply live; they simply don't survive a relaunch.
     *
     * Clears the in-memory map synchronously, so getters called immediately after return defaults.
     */
    fun resetToDefaults(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun isHeadFollowEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEAD_FOLLOW, true)

    fun setHeadFollowEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEAD_FOLLOW, enabled).apply()
    }

    fun isSmoothingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMOOTHING, true)

    fun setSmoothingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMOOTHING, enabled).apply()
    }

    fun getPanelDistance(context: Context): Float =
        prefs(context).getFloat(KEY_PANEL_DISTANCE, DEFAULT_PANEL_DISTANCE)
            .coerceIn(MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE)

    fun setPanelDistance(context: Context, distance: Float) {
        prefs(context).edit()
            .putFloat(KEY_PANEL_DISTANCE, distance.coerceIn(MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE))
            .apply()
    }

    fun getPanelScale(context: Context): Float =
        prefs(context).getFloat(KEY_PANEL_SCALE, DEFAULT_PANEL_SCALE)
            .coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE)

    fun setPanelScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_PANEL_SCALE, scale.coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE))
            .apply()
    }

    fun getPanelCurve(context: Context): Float =
        prefs(context).getFloat(KEY_PANEL_CURVE, DEFAULT_PANEL_CURVE)
            .coerceIn(MIN_PANEL_CURVE, MAX_PANEL_CURVE)

    fun setPanelCurve(context: Context, curve: Float) {
        prefs(context).edit()
            .putFloat(KEY_PANEL_CURVE, curve.coerceIn(MIN_PANEL_CURVE, MAX_PANEL_CURVE))
            .apply()
    }
}
