package dev.delpa.shimeji.overlay.awareness

import android.util.Log
import dev.delpa.shimeji.overlay.accessibility.AppCategory
import dev.delpa.shimeji.overlay.accessibility.ScreenContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Emergent behavior engine. The mascot has an internal arousal level that
 * fluctuates based on what's happening on screen. At each FSM step, the
 * engine rolls against arousal to decide whether to react and how.
 *
 * Nothing is deterministic. Same app opening twice produces different
 * reactions — sometimes the mascot reacts, sometimes it doesn't. This
 * makes it feel alive, not scripted.
 */
class ReactionEngine(
    private val random: Random = Random,
) {
    companion object {
        private const val TAG = "ReactionEngine"

        /** Arousal decay per second when nothing is happening. */
        private const val DECAY_RATE = 0.08f

        /** How fast arousal rises when something interesting happens. */
        private const val RISE_RATE = 0.4f

        /** Cooldown between reactions (milliseconds). */
        private const val REACTION_COOLDOWN_MS = 4000L

        /** Maximum arousal level. */
        private const val MAX_AROUSAL = 1.0f

        /** Minimum arousal level. */
        private const val MIN_AROUSAL = 0.0f
    }

    /**
     * Current arousal level. 0.0 = calm/bored, 1.0 = highly stimulated.
     * Fluctuates based on screen context and decays over time.
     */
    var arousal: Float = 0.2f
        private set

    /** Timestamp of last reaction fired (millis). */
    private var lastReactionAt = 0L

    /** Previous foreground package — detect app switches. */
    private var lastPackage: String? = null

    /** Previous notification state — detect new notifications. */
    private var lastHadNotification = false

    /**
     * Call every FSM step (~300ms). Returns a reaction decision:
     * - null = no reaction this step, mascot continues as-is
     * - non-null = mascot should transition to this animation state
     */
    fun step(context: ScreenContext, nowMs: Long): ReactionDecision? {
        // Update arousal based on context changes.
        updateArousal(context, nowMs)

        // Check cooldown.
        if (nowMs - lastReactionAt < REACTION_COOLDOWN_MS) return null

        // Decide whether to react at all, based on arousal + curiosity.
        val profile = if (context.isHomeScreen) {
            AppProfiles.HOME
        } else {
            AppProfiles.forCategory(context.category)
        }

        val baseReactionChance = arousal * 0.35f // max ~35% chance per step at full arousal
        val reactionChance = baseReactionChance * profile.curiosityMod

        if (random.nextFloat() > reactionChance) return null

        // Roll for which reaction to use.
        val reaction = pickReaction(profile, context) ?: return null

        lastReactionAt = nowMs
        Log.i(TAG, "arousal=${"%.2f".format(arousal)} -> reaction=$reaction (category=${context.category})")
        return reaction
    }

    private fun updateArousal(context: ScreenContext, nowMs: Long) {
        if (!context.screenOn) {
            arousal = MIN_AROUSAL
            return
        }

        val profile = if (context.isHomeScreen) {
            AppProfiles.HOME
        } else {
            AppProfiles.forCategory(context.category)
        }

        // App switch? Arousal changes based on the new app's profile.
        val pkg = context.foregroundPackage
        if (pkg != lastPackage) {
            lastPackage = pkg
            val delta = profile.arousalImpact * RISE_RATE
            arousal = (arousal + delta).coerceIn(MIN_AROUSAL, MAX_AROUSAL)
        }

        // New notification? Brief arousal spike.
        if (context.hasActiveNotification && !lastHadNotification) {
            arousal = (arousal + 0.3f).coerceIn(MIN_AROUSAL, MAX_AROUSAL)
        }
        lastHadNotification = context.hasActiveNotification

        // Natural decay toward a baseline.
        val baseline = if (profile.stillnessBias > 0.7f) 0.1f else 0.25f
        if (arousal > baseline) {
            arousal = max(arousal - DECAY_RATE * 0.3f, baseline)
        } else if (arousal < baseline) {
            arousal = min(arousal + DECAY_RATE * 0.1f, baseline)
        }
    }

    private fun pickReaction(profile: AppProfile, context: ScreenContext): ReactionDecision? {
        val candidates = profile.preferredReactions
        if (candidates.isEmpty()) return null

        // Weighted random pick, biased by arousal level.
        // Higher arousal → prefer more energetic reactions.
        val energetic = listOf("excited", "bounce", "jump", "dancing")
        val calm = listOf("calm_idle", "quiet_sit", "watching", "curious")

        val selected = if (arousal > 0.6f && random.nextFloat() < arousal) {
            // Prefer energetic reactions.
            val energeticCandidates = candidates.filter { it in energetic }
            if (energeticCandidates.isNotEmpty()) {
                energeticCandidates[random.nextInt(energeticCandidates.size)]
            } else {
                candidates[random.nextInt(candidates.size)]
            }
        } else if (arousal < 0.3f) {
            // Prefer calm reactions.
            val calmCandidates = candidates.filter { it in calm }
            if (calmCandidates.isNotEmpty()) {
                calmCandidates[random.nextInt(calmCandidates.size)]
            } else {
                candidates[random.nextInt(candidates.size)]
            }
        } else {
            candidates[random.nextInt(candidates.size)]
        }

        // Intensity: how long the reaction should last.
        // Higher arousal = longer, more noticeable reaction.
        val intensity = if (arousal > 0.7f) ReactionIntensity.HIGH
        else if (arousal > 0.4f) ReactionIntensity.MEDIUM
        else ReactionIntensity.LOW

        return ReactionDecision(
            animation = selected,
            intensity = intensity,
            arousalAtFire = arousal,
        )
    }

    /**
     * Force a reaction from outside (e.g. notification received).
     * Bypasses the normal probability check but respects cooldown.
     */
    fun forceReaction(animation: String, nowMs: Long): ReactionDecision? {
        if (nowMs - lastReactionAt < REACTION_COOLDOWN_MS / 2) return null
        lastReactionAt = nowMs
        return ReactionDecision(
            animation = animation,
            intensity = ReactionIntensity.HIGH,
            arousalAtFire = arousal,
        )
    }

    /** Reset state (e.g. when mascot is paused). */
    fun reset() {
        arousal = 0.2f
        lastReactionAt = 0L
        lastPackage = null
        lastHadNotification = false
    }
}

data class ReactionDecision(
    val animation: String,
    val intensity: ReactionIntensity,
    val arousalAtFire: Float,
)

enum class ReactionIntensity {
    LOW,     // Brief, subtle — a small fidget or glance
    MEDIUM,  // Noticeable — a bounce or head turn
    HIGH,    // Strong — a full excited reaction
}
