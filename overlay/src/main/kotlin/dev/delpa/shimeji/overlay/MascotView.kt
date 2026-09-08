package dev.delpa.shimeji.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import dev.delpa.shimeji.core.fsm.FsmConfig
import dev.delpa.shimeji.core.fsm.FsmEvent
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
    random: kotlin.random.Random = kotlin.random.Random,
) : View(context) {

    val fsm: ShimejiFSM = ShimejiFSM(fsmConfig, random)
    val state: PhysicsState = PhysicsState(x = 0f, y = 0f)

    private var bounds: PhysicsBounds? = null
    private var animPhaseMs = 0L
    private var lastAnimAdvanceMs = 0L
    private var completionFired = false
    private var fsmStepAccum = 0f

    /** Call per frame from the service driver. dtMs is a bounded, monotonic delta. */
    fun update(dtMs: Float, nowAnimMs: Long) {
        val b = bounds ?: return
        physics.step(state, b, dtMs / 1000f)
        physics.rest(state, b)

        // FSM stepping is decoupled from frame rate (300ms cadence).
        fsmStepAccum += dtMs
        if (fsmStepAccum >= 300f) {
            fsmStepAccum = 0f
            fsm.onStep(nowAnimMs, state.grounded, state.hangingLeft, state.hangingRight)
        }

        // Autonomous wander while grounded and walking.
        if (fsm.currentState.animation == "walk") {
            physics.wander(state, b, dtMs / 1000f)
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
        renderer.draw(
            canvas = canvas,
            width = width,
            height = height,
            state = PhysicsStatePublic.of(state.facingLeft),
            animationName = st.animation,
            frameIndex = currentFrameIndex,
            frameCount = st.frames.size,
            nowMs = System.currentTimeMillis(),
        )
    }
}