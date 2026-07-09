package com.compuglobal.astralprojector

import android.content.Context
import android.content.SharedPreferences

object SpatialControls {
    private const val PREFS_NAME = "spatial_controls"

    /** Key for the head-follow toggle; public so listeners can filter change callbacks. */
    const val KEY_HEAD_FOLLOW = "head_follow_enabled"
    private const val KEY_PANEL_DISTANCE = "panel_distance_m"

    const val DEFAULT_PANEL_DISTANCE = 2.0f
    private const val MIN_PANEL_DISTANCE = 0.5f
    private const val MAX_PANEL_DISTANCE = 5.0f

    /**
     * The single app-wide preferences instance. Both [MainActivity] (running as the panel) and
     * [ImmersiveActivity] share this process, so Android returns the same object here — change
     * listeners registered by one activity fire for edits made by the other.
     */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isHeadFollowEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEAD_FOLLOW, true)

    fun setHeadFollowEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEAD_FOLLOW, enabled).apply()
    }

    fun getPanelDistance(context: Context): Float =
        prefs(context).getFloat(KEY_PANEL_DISTANCE, DEFAULT_PANEL_DISTANCE)
            .coerceIn(MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE)

    fun setPanelDistance(context: Context, distance: Float) {
        prefs(context).edit()
            .putFloat(KEY_PANEL_DISTANCE, distance.coerceIn(MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE))
            .apply()
    }
}
