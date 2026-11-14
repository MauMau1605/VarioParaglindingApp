package com.vario.app

import android.content.Context

/**
 * Settings for paraglider glide calculations and map reachability heatmap.
 */
data class GlideSettings(
    val glideRatio: Float = 8.5f,
    val isGlideAreaEnabled: Boolean = true,
    val safetyMarginM: Float = 50f
)

/**
 * Persistence manager for glide parameters.
 */
object GlideSettingsManager {
    private const val PREFS_NAME = "vario_glide_prefs"
    private const val KEY_GLIDE_RATIO = "glide_ratio"
    private const val KEY_GLIDE_ENABLED = "glide_area_enabled"
    private const val KEY_SAFETY_MARGIN = "safety_margin_m"

    fun getSettings(context: Context): GlideSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GlideSettings(
            glideRatio = prefs.getFloat(KEY_GLIDE_RATIO, 8.5f),
            isGlideAreaEnabled = prefs.getBoolean(KEY_GLIDE_ENABLED, true),
            safetyMarginM = prefs.getFloat(KEY_SAFETY_MARGIN, 50f)
        )
    }

    fun saveSettings(context: Context, settings: GlideSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_GLIDE_RATIO, settings.glideRatio)
            .putBoolean(KEY_GLIDE_ENABLED, settings.isGlideAreaEnabled)
            .putFloat(KEY_SAFETY_MARGIN, settings.safetyMarginM)
            .apply()
    }
}
