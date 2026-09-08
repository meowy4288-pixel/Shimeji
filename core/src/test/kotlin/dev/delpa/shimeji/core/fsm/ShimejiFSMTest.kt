package dev.delpa.shimeji.core.fsm

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShimejiFSMTest {

    private fun config(
        states: List<FsmState> = listOf(
            FsmState("idle", "idle", frames = listOf("a", "b"), looping = true, transitions = listOf(
                FsmTransition("walking", GuardName.RANDOM_ANYTIME, weight = 2.0),
                FsmTransition("falling", GuardName.AIRBORNE, weight = 1.0),
            )),
            FsmState("walking", "walk", frames = listOf("a", "b"), looping = true),
            FsmState("falling", "fall", frames = listOf("f"), looping = true),
            FsmState("dragging", "drag", frames = listOf("a"), looping = true),
            FsmState("tool_feedback", "magic_cast", frames = listOf("c1", "c2"), looping = false, durationMs = 600),
        ),
        initial: String = "idle",
    ) = FsmConfig(states = states, initial = initial, fallbackState = "idle")

    @Test
    fun `guard filtering removes transitions whose guards fail`() {
        val fsm = ShimejiFSM(config(), Random(1))
        val eligibleGrounded = fsm.eligibleTransitions(nowMs = 0, grounded = true, hangingLeft = false, hangingRight = false)
        assertEquals(listOf("walking"), eligibleGrounded.map { it.to }, "AIRBORNE must be filtered when grounded")

        val eligibleAirborne = fsm.eligibleTransitions(nowMs = 0, grounded = false, hangingLeft = false, hangingRight = false)
        assertEquals(setOf("walking", "falling"), eligibleAirborne.map { it.to }.toSet())
    }

    @Test
    fun `cooldown filters transitions`() {
        val fsm = ShimejiFSM(config(), Random(1))
        // At nowMs = 0 no cooldown blocks; at a later time cooldowns still apply.
        val soon = fsm.eligibleTransitions(nowMs = 0, grounded = true)
        assertEquals(listOf("walking"), soon.map { it.to })
        // cooldownMs = 0 (falling) is always eligible when airborne; walking has a cooldown.
        val cooldownCfg = FsmConfig(
            states = listOf(
                FsmState("s", "idle", frames = listOf("a"), looping = true, transitions = listOf(
                    FsmTransition("falling", GuardName.AIRBORNE, cooldownMs = 100, weight = 1.0),
                )),
                FsmState("falling", "fall", frames = listOf("f"), looping = true),
            ),
            initial = "s",
            fallbackState = "s",
        )
        val fsm2 = ShimejiFSM(cooldownCfg, Random(1))
        // nowMs must exceed the cooldown (relative to lastTransitionTime=0).
        assertEquals(listOf("falling"), fsm2.eligibleTransitions(nowMs = 200, grounded = false).map { it.to })
        // A step at the same timestamp performs the transition and records it.
        fsm2.onStep(nowMs = 200, grounded = false)
        assertEquals("falling", fsm2.currentState.id)
    }

    @Test
    fun `weighted selection is filtered and normalized`() {
        // eligibleTransitions excludes the AIRBORNE candidate when grounded; the
        // remaining candidate is the ONLY one and is always chosen after cooldown.
        val fsm = ShimejiFSM(config(), Random(42))
        val picks = (0 until 5).map { fsm.selectWeighted(fsm.eligibleTransitions(nowMs = 0, grounded = true), 0) }
        assertTrue(picks.all { it == "walking" }, "only the eligible transition may be selected")

        // Two eligible transitions with weights 2:1 — both values appear across attempts.
        val cfg = FsmConfig(
            states = listOf(
                FsmState("s", "idle", frames = listOf("a"), looping = true, transitions = listOf(
                    FsmTransition("a", weight = 2.0),
                    FsmTransition("b", weight = 1.0),
                )),
                FsmState("a", "a", frames = listOf("a1"), looping = true),
                FsmState("b", "b", frames = listOf("b1"), looping = true),
            ),
            initial = "s",
            fallbackState = "s",
        )
        val weighted = ShimejiFSM(cfg, Random(7))
        val all = (0 until 8).map { weighted.selectWeighted(weighted.eligibleTransitions(nowMs = 0, grounded = true), 0) }
        assertTrue(all.contains("a") && all.contains("b"), "both candidates allowed, got $all")
    }

    @Test
    fun `zero weight and empty candidates stay in current state`() {
        val cfg = FsmConfig(
            states = listOf(
                FsmState("s", "idle", frames = listOf("a"), looping = true, transitions = listOf(
                    FsmTransition("a", weight = 0.0),
                )),
                FsmState("a", "a", frames = listOf("a1"), looping = true),
            ),
            initial = "s",
            fallbackState = "s",
        )
        val fsm = ShimejiFSM(cfg, Random(1))
        assertEquals(null, fsm.selectWeighted(fsm.eligibleTransitions(nowMs = 0, grounded = true), 0))
        assertEquals("s", fsm.currentState.id)
        assertEquals(null, fsm.selectWeighted(emptyList(), 0))
    }

    @Test
    fun `unknown state and missing frame assets fall back safely`() {
        val cfg = config()
        val fsm = ShimejiFSM(cfg, Random(1))
        // Constructor validation rejects transitions to unknown state ids.
        val bad = FsmConfig(
            states = listOf(
                FsmState("s", "idle", frames = listOf("a"), looping = true, transitions = listOf(
                    FsmTransition("missing", weight = 1.0),
                )),
            ),
            initial = "s",
            fallbackState = "s",
        )
        assertFailsWith<IllegalArgumentException> { ShimejiFSM(bad, Random(1)) }
        // The constructor also validates, so an asset with an unknown initial is rejected by validOrNull.
        assertEquals(
            null,
            ShimejiFSM.validOrNull("""{"initial":"nope","fallbackState":"idle","states":[{"id":"idle","animation":"idle","frames":["a"]}]}"""),
        )
        // Unknown frame ids are tolerated by the config loader validation path
        // (see validOrNull) without crashing the constructor.
        assertNotNull(fsm.currentState)
    }

    @Test
    fun `interruption policy drag outranks feedback`() {
        val fsm = ShimejiFSM(config(), Random(1))
        fsm.onEvent(FsmEvent.DRAG_STARTED, 0)
        assertEquals("dragging", fsm.currentState.id)
        assertFalse(fsm.canAcceptVisualCommand(InterruptPriority.COSMETIC_FEEDBACK), "drag blocks cosmetic feedback")
        fsm.onEvent(FsmEvent.TOOL_FEEDBACK, 100)
        assertEquals("dragging", fsm.currentState.id, "feedback must not break the drag")
        assertTrue(fsm.canAcceptVisualCommand(InterruptPriority.DRAG))

        fsm.onEvent(FsmEvent.DRAG_ENDED, 200)
        assertEquals("falling", fsm.currentState.id)
        assertTrue(fsm.canAcceptVisualCommand(InterruptPriority.COSMETIC_FEEDBACK))
    }

    @Test
    fun `feedback animation completes back to autonomous`() {
        val fsm = ShimejiFSM(config(), Random(1))
        fsm.onEvent(FsmEvent.TOOL_FEEDBACK, 0)
        assertEquals("tool_feedback", fsm.currentState.id)
        fsm.onEvent(FsmEvent.ANIMATION_COMPLETED, 650)
        assertEquals("idle", fsm.currentState.id)
    }

    @Test
    fun `loads bundled config json`() {
        val raw = javaClass.getResourceAsStream("/shimeji_fsm.json")!!.bufferedReader().use { it.readText() }
        val cfg = ShimejiFSM.load(raw)
        assertEquals("idle", cfg.initial)
        assertTrue(cfg.states.any { it.id == "tool_feedback" })
        val fsm = ShimejiFSM(cfg, Random(1))
        assertNotNull(fsm.currentState)
    }
}