package dev.delpa.shimeji.overlay.awareness

import dev.delpa.shimeji.overlay.accessibility.AppCategory

/**
 * Per-category behavior tuning. Controls how the mascot reacts to different
 * types of apps. Not per-package — categories keep the config small and
 * extensible without maintaining a giant package list.
 */
data class AppProfile(
    /** How much arousal changes when this category becomes foreground (0.0–1.0). */
    val arousalImpact: Float,
    /** Probability multiplier for reactions (1.0 = baseline, 2.0 = twice as reactive). */
    val curiosityMod: Float,
    /** Preferred reaction animations (weighted random selection). */
    val preferredReactions: List<String>,
    /** Whether the mascot tends to stay still or move in this category. */
    val stillnessBias: Float, // 0.0 = wants to move, 1.0 = wants to stay still
)

object AppProfiles {

    private val profiles = mapOf(
        AppCategory.SOCIAL to AppProfile(
            arousalImpact = 0.6f,
            curiosityMod = 1.5f,
            preferredReactions = listOf("excited", "curious", "bounce", "poke"),
            stillnessBias = 0.3f,
        ),
        AppCategory.MESSAGING to AppProfile(
            arousalImpact = 0.7f,
            curiosityMod = 1.8f,
            preferredReactions = listOf("excited", "bounce", "poke", "jump"),
            stillnessBias = 0.2f,
        ),
        AppCategory.MUSIC to AppProfile(
            arousalImpact = 0.4f,
            curiosityMod = 1.2f,
            preferredReactions = listOf("dancing", "excited", "calm_idle"),
            stillnessBias = 0.4f,
        ),
        AppCategory.VIDEO to AppProfile(
            arousalImpact = 0.3f,
            curiosityMod = 0.8f,
            preferredReactions = listOf("watching", "calm_idle", "curious"),
            stillnessBias = 0.8f,
        ),
        AppCategory.GAMES to AppProfile(
            arousalImpact = 0.5f,
            curiosityMod = 1.3f,
            preferredReactions = listOf("watching", "curious", "excited"),
            stillnessBias = 0.6f,
        ),
        AppCategory.PRODUCTIVITY to AppProfile(
            arousalImpact = 0.2f,
            curiosityMod = 0.5f,
            preferredReactions = listOf("calm_idle", "quiet_sit"),
            stillnessBias = 0.9f,
        ),
        AppCategory.CAMERA to AppProfile(
            arousalImpact = 0.1f,
            curiosityMod = 0.3f,
            preferredReactions = listOf("calm_idle"),
            stillnessBias = 0.95f,
        ),
        AppCategory.SHOPPING to AppProfile(
            arousalImpact = 0.4f,
            curiosityMod = 1.0f,
            preferredReactions = listOf("curious", "excited", "poke"),
            stillnessBias = 0.5f,
        ),
        AppCategory.NEWS to AppProfile(
            arousalImpact = 0.3f,
            curiosityMod = 0.7f,
            preferredReactions = listOf("curious", "watching", "calm_idle"),
            stillnessBias = 0.7f,
        ),
        AppCategory.SYSTEM to AppProfile(
            arousalImpact = 0.1f,
            curiosityMod = 0.3f,
            preferredReactions = listOf("calm_idle"),
            stillnessBias = 0.9f,
        ),
        AppCategory.UNKNOWN to AppProfile(
            arousalImpact = 0.3f,
            curiosityMod = 1.0f,
            preferredReactions = listOf("curious", "poke", "idle"),
            stillnessBias = 0.5f,
        ),
    )

    /** Home screen — mascot is relaxed, free to wander. */
    val HOME = AppProfile(
        arousalImpact = -0.1f, // slight arousal decrease — calming
        curiosityMod = 0.6f,
        preferredReactions = listOf("idle", "walking", "sit", "dangle"),
        stillnessBias = 0.2f,
    )

    fun forCategory(category: AppCategory): AppProfile =
        profiles[category] ?: profiles[AppCategory.UNKNOWN]!!

    /**
     * Map a package name to a category. Uses substring matching against
     * known package name patterns — not a huge list, just common ones.
     */
    fun categoryForPackage(packageName: String): AppCategory {
        val pkg = packageName.lowercase()
        return when {
            // Messaging
            pkg.contains("whatsapp") || pkg.contains("telegram") ||
                pkg.contains("signal") || pkg.contains("messaging") ||
                pkg.contains("messenger") || pkg.contains("discord") ||
                pkg.contains("slack") || pkg.contains("chat") -> AppCategory.MESSAGING

            // Social
            pkg.contains("instagram") || pkg.contains("twitter") ||
                pkg.contains("tiktok") || pkg.contains("facebook") ||
                pkg.contains("reddit") || pkg.contains("mastodon") ||
                pkg.contains("threads") || pkg.contains("snap") -> AppCategory.SOCIAL

            // Music
            pkg.contains("spotify") || pkg.contains("music") ||
                pkg.contains("soundcloud") || pkg.contains("deezer") ||
                pkg.contains("pandora") || pkg.contains("audioid") -> AppCategory.MUSIC

            // Video
            pkg.contains("youtube") || pkg.contains("netflix") ||
                pkg.contains("twitch") || pkg.contains("tiktok") ||
                pkg.contains("prime") || pkg.contains("hulu") ||
                pkg.contains("plex") || pkg.contains("vlc") -> AppCategory.VIDEO

            // Games
            pkg.contains("game") || pkg.contains("play.google.android.apps.games") ||
                pkg.contains("supercell") || pkg.contains("mojang") ||
                pkg.contains("riot") || pkg.contains("ea.") -> AppCategory.GAMES

            // Productivity
            pkg.contains("docs") || pkg.contains("sheets") ||
                pkg.contains("slides") || pkg.contains("office") ||
                pkg.contains("notion") || pkg.contains("todoist") ||
                pkg.contains("calendar") || pkg.contains("outlook") ||
                pkg.contains("drive") || pkg.contains("onedrive") -> AppCategory.PRODUCTIVITY

            // Camera
            pkg.contains("camera") || pkg.contains("snapseed") ||
                pkg.contains("lightroom") || pkg.contains("photoshop") -> AppCategory.CAMERA

            // Shopping
            pkg.contains("amazon") || pkg.contains("shop") ||
                pkg.contains("ebay") || pkg.contains("aliexpress") ||
                pkg.contains("store") || pkg.contains("pay") -> AppCategory.SHOPPING

            // News
            pkg.contains("news") || pkg.contains("reader") ||
                pkg.contains("rss") || pkg.contains("medium") ||
                pkg.contains("flipboard") -> AppCategory.NEWS

            // System
            pkg.contains("settings") || pkg.contains("launcher") ||
                pkg.contains("systemui") || pkg.contains("dialer") ||
                pkg.contains("contacts") || pkg.contains("filemanager") ||
                pkg.contains("files") -> AppCategory.SYSTEM

            else -> AppCategory.UNKNOWN
        }
    }
}
