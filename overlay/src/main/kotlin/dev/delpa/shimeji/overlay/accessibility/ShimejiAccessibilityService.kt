package dev.delpa.shimeji.overlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityService that gives the mascot awareness of the screen.
 *
 * This is the single source of truth for everything the mascot "sees":
 * - Which app is in the foreground (TYPE_WINDOW_STATE_CHANGED)
 * - Whether something changed on screen (TYPE_WINDOW_CONTENT_CHANGED)
 * - Notification events (TYPE_NOTIFICATION_STATE_CHANGED)
 * - Full node tree via [getRootInActiveWindow]
 * - Gesture dispatch via [dispatchGesture]
 *
 * The service communicates with the overlay via a static instance reference
 * and broadcasts [ScreenContext] updates through a simple callback.
 */
class ShimejiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ShimejiA11y"

        /** Static reference for the overlay to read current context. */
        @Volatile
        var instance: ShimejiAccessibilityService? = null
            private set

        /** Check if the service is enabled and running. */
        val isRunning: Boolean get() = instance != null

        /** Current screen context, readable from anywhere. */
        @Volatile
        var currentContext: ScreenContext = ScreenContext()
            private set

        /** Callback for screen context changes. */
        var onContextChanged: ((ScreenContext) -> Unit)? = null

        /** Request the service to perform a global action (back, home, recents). */
        fun performGlobal(action: Int): Boolean {
            return instance?.performGlobalAction(action) ?: false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Configure which event types we want to receive.
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        Log.i(TAG, "accessibility service connected")
        updateContext(foregroundPackage = null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                val cls = event.className?.toString()

                // Home screen / launcher = null package or launcher class.
                val isHome = cls?.contains("Launcher") == true ||
                    cls?.contains("Home") == true ||
                    pkg == null

                val resolvedPkg = if (isHome) null else pkg
                updateContext(foregroundPackage = resolvedPkg)
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // A notification appeared — bump arousal.
                currentContext = currentContext.copy(hasActiveNotification = true)
                onContextChanged?.invoke(currentContext)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Something changed on screen — minor arousal nudge.
                // We don't update the full context here, just signal activity.
                currentContext = currentContext.copy(lastChangedAt = System.currentTimeMillis())
            }
        }
    }

    private fun updateContext(foregroundPackage: String?) {
        val category = if (foregroundPackage != null) {
            AppProfileMapper.categoryForPackage(foregroundPackage)
        } else {
            AppCategory.UNKNOWN
        }

        currentContext = ScreenContext(
            foregroundPackage = foregroundPackage,
            category = category,
            hasActiveNotification = currentContext.hasActiveNotification,
            lastChangedAt = System.currentTimeMillis(),
            screenOn = true,
        )

        Log.d(TAG, "context: pkg=$foregroundPackage category=$category")
        onContextChanged?.invoke(currentContext)
    }

    override fun onInterrupt() {
        Log.i(TAG, "accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "accessibility service destroyed")
    }

    /**
     * Get the root node of the active window. Returns null if unavailable.
     * Useful for screen content analysis in Phase 3.
     */
    fun getScreenContent(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "failed to get root node", e)
            null
        }
    }
}

/** Map AppCategory from the accessibility service's package detection. */
private object AppProfileMapper {
    fun categoryForPackage(packageName: String): AppCategory {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("whatsapp") || pkg.contains("telegram") ||
                pkg.contains("signal") || pkg.contains("messaging") ||
                pkg.contains("messenger") || pkg.contains("discord") ||
                pkg.contains("chat") -> AppCategory.MESSAGING

            pkg.contains("instagram") || pkg.contains("twitter") ||
                pkg.contains("tiktok") || pkg.contains("facebook") ||
                pkg.contains("reddit") || pkg.contains("threads") ||
                pkg.contains("snap") -> AppCategory.SOCIAL

            pkg.contains("spotify") || pkg.contains("deezer") ||
                pkg.contains("soundcloud") || pkg.contains("pandora") -> AppCategory.MUSIC

            pkg.contains("youtube") || pkg.contains("netflix") ||
                pkg.contains("twitch") || pkg.contains("plex") ||
                pkg.contains("vlc") || pkg.contains("prime") ||
                pkg.contains("hulu") -> AppCategory.VIDEO

            pkg.contains("game") || pkg.contains("supercell") ||
                pkg.contains("mojang") || pkg.contains("riot") ||
                pkg.contains("ea.") -> AppCategory.GAMES

            pkg.contains("docs") || pkg.contains("sheets") ||
                pkg.contains("office") || pkg.contains("notion") ||
                pkg.contains("todoist") || pkg.contains("calendar") ||
                pkg.contains("drive") -> AppCategory.PRODUCTIVITY

            pkg.contains("camera") || pkg.contains("snapseed") ||
                pkg.contains("lightroom") -> AppCategory.CAMERA

            pkg.contains("amazon") || pkg.contains("shop") ||
                pkg.contains("ebay") || pkg.contains("store") ||
                pkg.contains("pay") -> AppCategory.SHOPPING

            pkg.contains("news") || pkg.contains("reader") ||
                pkg.contains("medium") || pkg.contains("flipboard") -> AppCategory.NEWS

            pkg.contains("settings") || pkg.contains("launcher") ||
                pkg.contains("systemui") || pkg.contains("dialer") ||
                pkg.contains("contacts") || pkg.contains("files") -> AppCategory.SYSTEM

            else -> AppCategory.UNKNOWN
        }
    }
}
