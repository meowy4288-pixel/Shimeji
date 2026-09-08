package dev.delpa.shimeji.core.fsm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Host-independent animation/behavior FSM. Physics numbers live in
 * [dev.delpa.shimeji.core.physics.PhysicsEngine]; this file orchestrates
 * state + transition logic only and defines the validated JSON config format.
 *
 * Guard vocabulary is a small sealed set — arbitrary expressions are never
 * evaluated from asset files.
 */

@Serializable
data class FsmConfig(
    val states: List<FsmState>,
    val initial: String = "idle",
    val fallbackState: String = "idle",
    val gravity: Double = 1500.0,
    val maxFallSpeed: Double = 900.0,
) {
    val stateById: Map<String, FsmState> = states.associateBy { it.id }
}

@Serializable
data class FsmState(
    val id: String,
    val animation: String,
    val frameDurationMs: Long = 200,
    val frames: List<String> = emptyList(),
    val looping: Boolean = true,
    val transitions: List<FsmTransition> = emptyList(),
    val durationMs: Long? = null,
)

@Serializable
data class FsmTransition(
    val to: String,
    val guard: GuardName? = null,
    val cooldownMs: Long = 0,
    /** Non-negative relative weight; normalized across eligible candidates. */
    val weight: Double = 1.0,
)

@Serializable
enum class GuardName {
    GROUNDED,
    AIRBORNE,
    NEAR_LEFT_CLIMB,
    NEAR_RIGHT_CLIMB,
    RANDOM_ANYTIME,
}

/** Interrupt priority: higher wins. Dragging outranks cosmetic feedback. */
@Serializable
enum class InterruptPriority(val level: Int) {
    AUTONOMOUS(0),
    COSMETIC_FEEDBACK(1),
    DRAG(2),
}

enum class FsmEvent {
    STEP,
    DRAG_STARTED,
    DRAG_ENDED,
    TOOL_FEEDBACK,
    ANIMATION_COMPLETED,
}

class ShimejiFSM(
    config: FsmConfig,
    private val random: kotlin.random.Random = kotlin.random.Random,
) {
    val config: FsmConfig = validate(config)

    private var currentStateId: String = config.initial.takeIf { it in config.stateById } ?: config.fallbackState
    private var lastTransitionTime: Long = 0L
    private var dragActive: Boolean = false
    private var feedbackLockUntil: Long = 0L

    val currentState: FsmState get() = config.stateById[currentStateId] ?: config.stateById[config.fallbackState]!!

    /**
     * Step the FSM. [grounded] and [canClimb] feed the enum guards; the FSM
     * itself does not know about pixels.
     */
    fun onStep(
        nowMs: Long,
        grounded: Boolean,
        hangingLeft: Boolean = false,
        hangingRight: Boolean = false,
    ): FsmState {
        // Dragging is sticky: it outranks everything while active.
        if (dragActive) return currentState

        val eligible = eligibleTransitions(nowMs, grounded, hangingLeft, hangingRight)
        val nextId = selectWeighted(eligible) ?: currentStateId
        if (nextId != currentStateId) {
            currentStateId = nextId
            lastTransitionTime = nowMs
        }
        return currentState
    }

    fun onEvent(event: FsmEvent, nowMs: Long): FsmState {
        when (event) {
            FsmEvent.DRAG_STARTED -> {
                dragActive = true
                currentStateId = "dragging"
            }
            FsmEvent.DRAG_ENDED -> {
                dragActive = false
                // Release: physics decides fall vs ground; we default to falling
                // and physics grounds us into idle via guards.
                currentStateId = "falling"
                lastTransitionTime = nowMs
            }
            FsmEvent.TOOL_FEEDBACK -> {
                if (!dragActive && nowMs >= feedbackLockUntil) {
                    currentStateId = "tool_feedback"
                    feedbackLockUntil = nowMs + 500
                    lastTransitionTime = nowMs
                }
            }
            FsmEvent.ANIMATION_COMPLETED -> {
                // Non-looping states fall back to autonomous candidates.
                if (!currentState.looping && currentStateId != "dragging") {
                    currentStateId = config.fallbackState
                    lastTransitionTime = nowMs
                }
            }
            FsmEvent.STEP -> Unit // autonomous stepping is driven by onStep()
        }
        return currentState
    }

    /** Interrupt policy: dragging outranks cosmetic feedback. */
    fun canAcceptVisualCommand(priority: InterruptPriority): Boolean {
        if (dragActive && priority != InterruptPriority.DRAG) return false
        return priority.level >= InterruptPriority.COSMETIC_FEEDBACK.level
    }

    /** Force a named state (used by tool-feedback visuals). */
    fun forceState(id: String, nowMs: Long): Boolean {
        if (id !in config.stateById) return false
        if (dragActive && id != "dragging") return false
        currentStateId = id
        lastTransitionTime = nowMs
        return true
    }

    internal fun eligibleTransitions(
        nowMs: Long,
        grounded: Boolean = true,
        hangingLeft: Boolean = false,
        hangingRight: Boolean = false,
    ): List<FsmTransition> {
        val state = currentState
        return state.transitions.filter { t ->
            t.cooldownMs <= 0 || nowMs - lastTransitionTime >= t.cooldownMs
        }.filter { t ->
            guardSatisfied(t.guard, grounded, hangingLeft, hangingRight)
        }.filter { t ->
            t.to in config.stateById
        }
    }

    /**
     * Weighted selection over ELIGIBLE candidates only.
     *  - relative weights are normalized across eligible transitions, so
     *    weight 2 is twice as likely as weight 1 — they are NOT unconditional
     *    percentages (a weight of 90 does not mean 90%).
     *  - zero total / empty candidates => null (stay in current state).
     *  - invalid numbers already rejected at config validation.
     */
    internal fun selectWeighted(candidates: List<FsmTransition>): String? {
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { it.weight }
        if (total <= 0.0) return null
        var pick = random.nextDouble() * total
        for (c in candidates) {
            pick -= c.weight
            if (pick <= 0.0) return c.to
        }
        return candidates.last().to
    }

    private fun guardSatisfied(
        guard: GuardName?,
        grounded: Boolean,
        hangingLeft: Boolean,
        hangingRight: Boolean,
    ): Boolean = when (guard) {
        null -> true
        GuardName.GROUNDED -> grounded
        GuardName.AIRBORNE -> !grounded
        GuardName.NEAR_LEFT_CLIMB -> hangingLeft
        GuardName.NEAR_RIGHT_CLIMB -> hangingRight
        GuardName.RANDOM_ANYTIME -> true
    }

    private fun validate(config: FsmConfig): FsmConfig {
        require(config.states.isNotEmpty()) { "FSM config has no states" }
        val ids = config.states.map { it.id }.toSet()
        require(config.initial in ids) { "initial state '${config.initial}' unknown" }
        require(config.fallbackState in ids) { "fallback '${config.fallbackState}' unknown" }
        require(config.gravity > 0.0 && config.gravity.isFinite()) { "gravity must be finite positive" }
        require(config.maxFallSpeed > 0.0 && config.maxFallSpeed.isFinite()) { "maxFallSpeed must be finite positive" }
        for (s in config.states) {
            require(s.frames.isNotEmpty()) { "state '${s.id}' has no frame references" }
            require(s.frameDurationMs > 0) { "state '${s.id}' frameDurationMs must be positive" }
            require(s.durationMs == null || s.durationMs > 0) { "state '${s.id}' durationMs must be positive" }
            for (t in s.transitions) {
                require(t.to in ids) { "state '${s.id}' references unknown target '${t.to}'" }
                require(t.weight >= 0.0 && t.weight.isFinite()) { "transition weight must be non-negative finite" }
                require(t.cooldownMs >= 0) { "cooldownMs must be non-negative" }
                require(t.guard == null || GuardName.entries.any { it.name == t.guard.name }) { "unknown guard" }
            }
        }
        return config
    }

    companion object {
        fun load(rawJson: String): FsmConfig =
            Json { ignoreUnknownKeys = true }.decodeFromString<FsmConfig>(rawJson)

        fun validOrNull(rawJson: String): FsmConfig? = try {
            val cfg = Json { ignoreUnknownKeys = true }.decodeFromString<FsmConfig>(rawJson)
            // Full constructor validation (frames, weights, fallback, gravity,
            // transition targets) without throwing into the caller.
            ShimejiFSM(cfg)
            cfg
        } catch (t: Throwable) {
            null
        }
    }
}