package dev.delpa.shimeji.core.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicsEngineTest {

    private fun bounds(w: Float = 1000f, h: Float = 2000f) = PhysicsBounds(
        left = 0f,
        top = 0f,
        right = w,
        bottom = h,
        displayWidth = w,
        displayHeight = h,
    )

    @Test
    fun `falls and rests exactly on the ground`() {
        val engine = PhysicsEngine()
        val b = bounds()
        val s = PhysicsState(x = 100f, y = 0f, vy = 0f)
        repeat(400) { engine.step(s, b, 1f / 60f) }
        engine.rest(s, b)
        assertEquals(b.bottom, s.y, 0.02f)
        assertTrue(s.grounded)
        assertEquals(0f, s.vy)
    }

    @Test
    fun `wall collision clamps position and reverses damped velocity`() {
        val engine = PhysicsEngine()
        val b = bounds(400f, 2000f)
        val s = PhysicsState(x = 395f, y = 100f, vx = 500f, vy = 0f)
        engine.step(s, b, 1f / 60f)
        assertTrue(s.x <= b.right + 0.01f)
        assertTrue(s.vx <= 0f, "velocity must reverse off the wall, got vx=${s.vx}")
        assertTrue(s.hangingRight, "collision with the right wall must set hanging flag")
    }

    @Test
    fun `safe timestep bounds huge deltas`() {
        val engine = PhysicsEngine()
        val b = bounds()
        // A single huge delta is capped to one max substep (no teleport, no NaN).
        val s = PhysicsState(x = 10f, y = 0f, vy = 0f)
        engine.step(s, b, 10f)
        assertFalse(s.y.isNaN())
        assertTrue(s.y <= b.bottom + 0.01f)
        // Capped single-substep movement: vy = g * maxDt, y = vy * maxDt.
        assertEquals((1500f * (1f / 20f)) * (1f / 20f), s.y, 0.001f)
        // Many frames with valid deltas eventually land exactly on the ground.
        val s2 = PhysicsState(x = 10f, y = 0f, vy = 0f)
        repeat(600) { engine.step(s2, b, 1f / 60f) }
        engine.rest(s2, b)
        assertEquals(b.bottom, s2.y, 0.02f)
        assertTrue(s2.grounded)
    }

    @Test
    fun `release velocity is capped`() {
        val engine = PhysicsEngine(maxReleaseSpeed = 100f)
        val s = PhysicsState(x = 0f, y = 0f)
        engine.releaseFromDrag(s, 5000f, -5000f)
        assertEquals(100f, s.vx, 0.01f)
        assertEquals(-100f, s.vy, 0.01f)
    }

    @Test
    fun `drag input drives position and clamps to bounds`() {
        val engine = PhysicsEngine()
        val b = bounds()
        val s = PhysicsState(x = 0f, y = 0f)
        engine.step(s, b, 1f / 60f, PhysicsEngine.Input.drag(999f, 1999f))
        assertEquals(999f, s.x)
        assertEquals(1999f, s.y)
        // Outside bounds clamps.
        engine.step(s, b, 1f / 60f, PhysicsEngine.Input.drag(5000f, 5000f))
        assertEquals(b.right - 1f, s.x)
        assertEquals(b.bottom - 1f, s.y)
    }

    @Test
    fun `stable resting does not sink with repeated frames`() {
        val engine = PhysicsEngine()
        val b = bounds()
        val s = PhysicsState(x = 10f, y = b.bottom)
        s.grounded = true
        repeat(300) { engine.step(s, b, 1f / 60f) }
        engine.rest(s, b)
        assertEquals(b.bottom, s.y, 0.02f)
        assertTrue(s.grounded)
        assertEquals(0f, s.vy)
    }

    @Test
    fun `zero and negative delta are no-ops`() {
        val engine = PhysicsEngine()
        val b = bounds()
        val s = PhysicsState(x = 1f, y = 1f, vx = 50f, vy = -50f)
        val before = s.copy()
        engine.step(s, b, 0f)
        engine.step(s, b, -1f)
        assertEquals(before.x, s.x)
        assertEquals(before.y, s.y)
    }

    @Test
    fun `walk intent then step does not double integrate`() {
        val engine = PhysicsEngine(moveSpeed = 100f)
        val b = bounds()
        val s = PhysicsState(x = 10f, y = b.bottom)
        s.grounded = true
        val dt = 1f / 60f

        // applyWalkIntent sets velocity but never touches position.
        engine.applyWalkIntent(s)
        assertEquals(100f, s.vx, 0.001f)
        assertEquals(10f, s.x, 0.001f)

        // A single step integrates exactly once: x += vx * dt.
        engine.step(s, b, dt)
        assertEquals(10f + 100f * dt, s.x, 0.001f, "position must advance exactly one integration")
    }
}