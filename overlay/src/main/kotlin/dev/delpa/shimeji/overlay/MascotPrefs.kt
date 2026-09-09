package dev.delpa.shimeji.overlay

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed toggles for mascot behaviors.
 * All defaults are `true` (everything enabled).
 * Read on every relevant frame/touch — SharedPreferences memory-maps the file
 * so there is no per-read cost beyond the map lookup.
 */
class MascotPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Tap the mascot → jump/bounce/poke reaction. */
    var tapPoke: Boolean
        get() = prefs.getBoolean(KEY_TAP_POKE, true)
        set(v) = prefs.edit().putBoolean(KEY_TAP_POKE, v).apply()

    /** Tap left/right side → mascot faces that direction. */
    var tapFacing: Boolean
        get() = prefs.getBoolean(KEY_TAP_FACING, true)
        set(v) = prefs.edit().putBoolean(KEY_TAP_FACING, v).apply()

    /** Autonomous walk when idle. */
    var walk: Boolean
        get() = prefs.getBoolean(KEY_WALK, true)
        set(v) = prefs.edit().putBoolean(KEY_WALK, v).apply()

    /** Idle variety (sit, dangle, lie, look_up). */
    var idleVariety: Boolean
        get() = prefs.getBoolean(KEY_IDLE_VARIETY, true)
        set(v) = prefs.edit().putBoolean(KEY_IDLE_VARIETY, v).apply()

    /** Selected character name (empty = bundled classic). */
    var selectedCharacter: String
        get() = prefs.getString(KEY_SELECTED_CHAR, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SELECTED_CHAR, v).apply()

    /** Mascot scale factor (0.5–1.5, default 1.0). */
    var mascotScale: Float
        get() = prefs.getFloat(KEY_MASCOT_SCALE, 1f)
        set(v) = prefs.edit().putFloat(KEY_MASCOT_SCALE, v).apply()

    companion object {
        private const val PREFS_NAME = "shimeji_mascot"
        private const val KEY_TAP_POKE = "tap_poke"
        private const val KEY_TAP_FACING = "tap_facing"
        private const val KEY_WALK = "walk"
        private const val KEY_IDLE_VARIETY = "idle_variety"
        private const val KEY_SELECTED_CHAR = "selected_character"
        private const val KEY_MASCOT_SCALE = "mascot_scale"
    }
}
