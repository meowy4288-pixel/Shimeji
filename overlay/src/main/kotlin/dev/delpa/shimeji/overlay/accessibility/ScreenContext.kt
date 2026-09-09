package dev.delpa.shimeji.overlay.accessibility

/**
 * Snapshot of what the mascot "sees" on screen right now.
 * Updated by the AccessibilityService, read by the ReactionEngine.
 */
data class ScreenContext(
    /** Current foreground app package name (null if unknown/home screen). */
    val foregroundPackage: String? = null,
    /** App category derived from package name. */
    val category: AppCategory = AppCategory.UNKNOWN,
    /** Whether a notification is currently visible in the status bar. */
    val hasActiveNotification: Boolean = false,
    /** Timestamp of the last screen change (epoch millis). */
    val lastChangedAt: Long = 0L,
    /** Whether the screen is on and interactive. */
    val screenOn: Boolean = true,
) {
    val isHomeScreen: Boolean get() = foregroundPackage == null
    val isKnownApp: Boolean get() = category != AppCategory.UNKNOWN
}

enum class AppCategory {
    SOCIAL,
    MUSIC,
    VIDEO,
    GAMES,
    PRODUCTIVITY,
    MESSAGING,
    CAMERA,
    SHOPPING,
    NEWS,
    SYSTEM,
    UNKNOWN,
}
