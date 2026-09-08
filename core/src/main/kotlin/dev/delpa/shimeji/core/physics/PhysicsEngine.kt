package dev.delpa.shimeji.core.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Frame-rate-independent 2D physics for the mascot.
 *
 * Coordinate contract: one explicit screen coordinate system for EVERYTHING.
 *  - Positions and sizes are in *physical pixels on the usable display area*
 *    (the same space the overlay View is positioned in).
 *  - Insets (system bars / cutouts / navigation) are subtracted ONCE when
 *    constructing [PhysicsBounds], and never subtracted again downstream.
 *  - Raw touch coordinates are already in the same space (the view moves via
 *    WindowManager in that space), so no second inset subtraction happens.
 *
 * Uses a monotonic clock for delta, bounded timestep, capped velocities,
 * stable resting with exact ground contact.
 */
data class PhysicsBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val displayWidth: Float,
    val displayHeight: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class PhysicsState(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var grounded: Boolean = false,
    var facingLeft: Boolean = false,
    var hangingLeft: Boolean = false,
    var hangingRight: Boolean = false,
)

class PhysicsEngine(
    private val random: kotlin.random.Random = kotlin.random.Random,
    private val gravity: Float = 1500f,
    private val maxFallSpeed: Float = 900f,
    private val moveSpeed: Float = 160f,
    private val maxReleaseSpeed: Float = 1100f,
    private val dragDamping: Float = 12f,
    private val bounce: Float = 0.12f,
    private val maxTimestepSeconds: Float = 1f / 20f,
) {
    /** Minimum separation after collision resolution, in pixels. */
    private val epsilon: Float = 0.01f

    fun step(state: PhysicsState, bounds: PhysicsBounds, dt: Float, input: Input = Input.none()) {
        var delta = dt
        // Bounded timestep: never catch up after suspension with a giant jump.
        delta = min(delta, maxTimestepSeconds)
        if (delta <= 0f) return

        when (input.mode) {
            InputMode.FREE -> {
                applyGravity(state, delta)
                integrate(state, bounds, delta)
                collide(state, bounds)
                if (state.grounded) {
                    state.vy = 0f
                }
            }
            InputMode.DRAG -> {
                // While dragging, position is driven by the touch handler.
                // Dampen velocity so release is not explosive.
                state.vx = 0f
                state.vy = 0f
                state.grounded = false
                state.x = input.targetX
                state.y = input.targetY
                clampToBounds(state, bounds)
            }
        }
    }

    /** Autonomous horizontal wander with random direction changes at walls. */
    fun wander(state: PhysicsState, bounds: PhysicsBounds, dt: Float) {
        val delta = min(dt, maxTimestepSeconds)
        if (state.grounded) {
            state.vx = moveSpeed * (if (state.facingLeft) -1f else 1f)
        } else {
            state.vx *= (1f - dragDamping * delta)
        }
        integrate(state, bounds, delta)
        collide(state, bounds)
    }

    fun releaseFromDrag(state: PhysicsState, rawVx: Float, rawVy: Float) {
        val cappedX = rawVx.coerceIn(-maxReleaseSpeed, maxReleaseSpeed)
        val cappedY = rawVy.coerceIn(-maxReleaseSpeed, maxReleaseSpeed)
        state.vx = cappedX
        state.vy = cappedY
        state.grounded = false
        state.hangingLeft = false
        state.hangingRight = false
    }

    fun clampToBounds(state: PhysicsState, bounds: PhysicsBounds) {
        state.x = state.x.coerceIn(bounds.left, bounds.right - 1)
        state.y = state.y.coerceIn(bounds.top, bounds.bottom - 1)
    }

    private fun applyGravity(state: PhysicsState, dt: Float) {
        if (!state.grounded) {
            state.vy = min(state.vy + gravity * dt, maxFallSpeed)
        }
    }

    private fun integrate(state: PhysicsState, bounds: PhysicsBounds, dt: Float) {
        state.x += state.vx * dt
        state.y += state.vy * dt
    }

    private fun collide(state: PhysicsState, bounds: PhysicsBounds) {
        state.grounded = false
        state.hangingLeft = false
        state.hangingRight = false

        if (state.y >= bounds.bottom - epsilon) {
            state.y = bounds.bottom - epsilon
            if (state.vy > 60f) {
                state.vy = -state.vy * bounce
            } else {
                state.vy = 0f
                state.grounded = true
            }
        }
        if (state.y <= bounds.top + epsilon) {
            state.y = bounds.top + epsilon
            state.vy = abs(state.vy) * bounce
        }

        if (state.x <= bounds.left + epsilon) {
            state.x = bounds.left + epsilon
            state.vx = abs(state.vx) * bounce
            if (abs(state.vx) < 30f) state.vx = 0f
            state.facingLeft = false
            state.hangingLeft = true
        }
        if (state.x >= bounds.right - epsilon) {
            state.x = bounds.right - epsilon
            state.vx = -abs(state.vx) * bounce
            if (abs(state.vx) < 30f) state.vx = 0f
            state.facingLeft = true
            state.hangingRight = true
        }
    }

    /** Resting logic: stick to ground exactly, never sink. */
    fun rest(state: PhysicsState, bounds: PhysicsBounds) {
        if (state.grounded && state.y > bounds.bottom - epsilon) {
            state.y = bounds.bottom - epsilon
        }
        if (!state.grounded && state.y >= bounds.bottom - 2f && abs(state.vy) < 40f) {
            state.y = bounds.bottom - epsilon
            state.grounded = true
            state.vy = 0f
        }
    }

    enum class InputMode { FREE, DRAG }
    data class Input(val mode: InputMode = InputMode.FREE, val targetX: Float = 0f, val targetY: Float = 0f) {
        companion object {
            fun none() = Input(InputMode.FREE)
            fun drag(x: Float, y: Float) = Input(InputMode.DRAG, x, y)
        }
    }
}