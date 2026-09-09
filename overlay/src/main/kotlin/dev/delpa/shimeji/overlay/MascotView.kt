package dev.delpa.shimeji.overlay

import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.view.View
import dev.delpa.shimeji.core.fsm.FsmConfig
import dev.delpa.shimeji.core.fsm.FsmEvent
import dev.delpa.shimeji.core.fsm.InterruptPriority
import dev.delpa.shimeji.core.fsm.ShimejiFSM
import dev.delpa.shimeji.core.physics.PhysicsBounds
import dev.delpa.shimeji.core.physics.PhysicsEngine
import dev.delpa.shimeji.core.physics.PhysicsState

/**
 * The mascot window content. Owns physics + FSM + sprite animation timing and
 * only draws; the Service owns the window, frame scheduling and touch events.
 */
class MascotView(
    context: Context,
    fsmConfig: FsmConfig,
    private val renderer: MascotRenderer,
    internal val physics: PhysicsEngine,
    internal val prefs: MascotPrefs = MascotPrefs(context),
    private val random: kotlin.random.Random = kotlin.random.Random,
) : View(context) {

    companion object {
        private const val TAG = "MascotView"

        /** Downward fall speed beyond which a fling landing trips the mascot. */
        private const val TRIP_IMPACT_VY = 1400f

        /** FSM states gated by the idle-variety toggle. */
        private val VARIETY_STATES = setOf("sit", "dangle", "lie", "look_up")
    }

    val fsm: ShimejiFSM = ShimejiFSM(fsmConfig, random)
    val state: PhysicsState = PhysicsState(x = 0f, y = 0f)

    /** Set by the Service's touch handler while the user is dragging. While
     *  true, physics runs in DRAG mode so gravity/integration stop fighting the
     *  touch-driven position. */
    var dragging = false
        set(value) {
            if (value && !field) {
                // Grabbing a falling mascot resets the fall-context so a gentle,
                // hand-placed release never trips the hard-landing animation.
                peakFallVy = 0f
            }
            field = value
        }

    private var bounds: PhysicsBounds? = null
    private var animPhaseMs = 0L
    private var lastAnimAdvanceMs = 0L
    private var completionFired = false
    private var fsmStepAccum = 0f
    private var lastStateId: String? = null
    private var lastGrounded = true
    private var peakFallVy = 0f

    /**
     * Tap interaction: pick a short reaction animation. Returns false when a
     * higher-priority visual (drag, tool feedback) is active.
     */
    fun poke(nowMs: Long): Boolean {
        if (!fsm.canAcceptVisualCommand(InterruptPriority.COSMETIC_FEEDBACK)) return false
        val reactions = listOf("jump", "bounce", "poke")
        val picked = reactions[random.nextInt(reactions.size)]
        val accepted = fsm.forceState(picked, nowMs)
        Log.i(TAG, "poke -> $picked (accepted=$accepted)")
        return accepted
    }

    /** Call per frame from the service driver. dtMs is a bounded, monotonic delta. */
    fun update(dtMs: Float, nowAnimMs: Long) {
        val b = bounds ?: return
        val wasGrounded = lastGrounded
        if (!state.grounded) peakFallVy = maxOf(peakFallVy, state.vy)

        // While dragging, physics pins the mascot to the touch position instead
        // of applying gravity/integration (which would make it fall between
        // touch events).
        if (dragging) {
            physics.step(state, b, dtMs / 1000f, PhysicsEngine.Input.drag(state.x, state.y))
        } else {
            physics.step(state, b, dtMs / 1000f)
            physics.rest(state, b)
        }

        // FSM stepping is decoupled from frame rate (300ms cadence).
        fsmStepAccum += dtMs
        if (fsmStepAccum >= 300f) {
            fsmStepAccum = 0f
            fsm.onStep(nowAnimMs, state.grounded, state.hangingLeft, state.hangingRight)
        }

        // Reset animation phase whenever the FSM state changes so non-looping
        // reactions (jump/poke/trip/magic_cast) play from their first frame.
        val st = fsm.currentState

        // Gate idle variety: if the user disabled variety, force back to idle
        // when the FSM autonomously picks sit/dangle/lie/look_up.
        if (!prefs.idleVariety && st.id in VARIETY_STATES) {
            fsm.forceState("idle", nowAnimMs)
        }
        // Gate walk: if the user disabled walking, force back to idle when the
        // FSM autonomously picks walking.
        if (!prefs.walk && fsm.currentState.id == "walking") {
            fsm.forceState("idle", nowAnimMs)
        }

        if (st.id != lastStateId) {
            Log.i(TAG, "state: ${lastStateId ?: "-"} -> ${st.id}")
            lastStateId = st.id
            animPhaseMs = 0L
            completionFired = false
        }

        // Hard landing: a very fast fling into the floor trips the mascot.
        if (!wasGrounded && state.grounded) {
            if (peakFallVy >= TRIP_IMPACT_VY &&
                fsm.canAcceptVisualCommand(InterruptPriority.COSMETIC_FEEDBACK)
            ) {
                fsm.forceState("trip", nowAnimMs)
            }
            peakFallVy = 0f
            lastGrounded = true
        } else {
            lastGrounded = state.grounded
        }

        // Autonomous walk intent only; physics.step() already integrated/collided above.
        if (fsm.currentState.animation == "walk" && prefs.walk) {
            physics.applyWalkIntent(state)
        } else if (state.grounded) {
            // Damp residual horizontal velocity while idling to avoid drifting.
            state.vx *= 0.90f
            if (kotlin.math.abs(state.vx) < 2f) state.vx = 0f
        }

        // Sprite animation timing is separate from physics timing.
        advanceAnimation(nowAnimMs)
    }

    private fun advanceAnimation(nowAnimMs: Long) {
        val st = fsm.currentState
        val elapsed = if (lastAnimAdvanceMs == 0L) 0L else (nowAnimMs - lastAnimAdvanceMs)
        lastAnimAdvanceMs = nowAnimMs
        animPhaseMs += elapsed.coerceIn(0L, 250L)

        val frames = st.frames
        if (frames.isEmpty()) return

        val frameIndex: Int
        val dur = st.durationMs
        if (st.looping) {
            frameIndex = ((animPhaseMs / st.frameDurationMs) % frames.size).toInt()
        } else {
            frameIndex = (animPhaseMs / st.frameDurationMs).toInt().coerceAtMost(frames.size - 1)
            if (dur != null && animPhaseMs >= dur && !completionFired) {
                completionFired = true
                fsm.onEvent(FsmEvent.ANIMATION_COMPLETED, nowAnimMs)
            }
        }
        currentFrameIndex = frameIndex
    }

    private var currentFrameIndex = 0

    // Avoids allocating a new PhysicsStatePublic per draw frame.
    private var cachedDrawState = PhysicsStatePublic.of(false)
    private var cachedFacingLeft: Boolean? = null

    fun setBounds(newBounds: PhysicsBounds, resetCompletion: Boolean = false) {
        bounds = newBounds
        physics.clampToBounds(state, newBounds)
        physics.rest(state, newBounds)
        if (resetCompletion) completionFired = false
    }

    fun forceState(id: String, nowMs: Long): Boolean = fsm.forceState(id, nowMs)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val st = fsm.currentState
        if (cachedFacingLeft != state.facingLeft) {
            cachedFacingLeft = state.facingLeft
            cachedDrawState = PhysicsStatePublic.of(state.facingLeft)
        }
        renderer.draw(
            canvas = canvas,
            width = width,
            height = height,
            state = cachedDrawState,
            animationName = st.animation,
            frameIndex = currentFrameIndex,
            frameCount = st.frames.size,
            nowMs = System.currentTimeMillis(),
        )
    }
}