package dev.delpa.shimeji.core.event

import dev.delpa.shimeji.core.harness.HarnessError
import dev.delpa.shimeji.core.harness.ToolRejectionReason
import dev.delpa.shimeji.core.harness.ToolResult

/**
 * Central typed event bus for the harness.
 *
 * Design notes (per milestone requirements):
 * - `StateFlow` is used for current *state* (plugin states, catalog revision).
 * - `SharedFlow` is used for one-shot *events* (tool calls, visual commands).
 * - Command events are non-replaying: recreated collectors observe only events
 *   emitted after they subscribe, so recreated collectors can never re-execute
 *   actions. The engine-owned submission path (see [submit] helpers) is the
 *   reliable delivery channel; the SharedFlow pipeline remains the inspectable
 *   event stream.
 *
 * Delivery guarantee is in-process only: SharedFlow with a replay buffer of 0
 * does not replay to late subscribers, and events are dropped if all collectors
 * are gone. The engine therefore treats emits as best-effort for *observers*,
 * while the actual requested action (tool dispatch, animation trigger) is
 * committed by the engine itself before emission. Confirmation of submission —
 * not of eventual observer delivery — is what [acknowledged emit] returns.
 */
interface EventBus {
    val events: kotlinx.coroutines.flow.SharedFlow<HarnessEvent>
    fun emit(event: HarnessEvent): Boolean
}

/**
 * Typed harness events. Every tool-related event carries its request ID.
 * Distinguishes: requested -> accepted/rejected -> (execution start) -> completed/failed.
 */
sealed interface HarnessEvent {
    data class ToolCallRequested(
        val requestId: String,
        val pluginId: String,
        val toolName: String,
        val params: kotlinx.serialization.json.JsonObject,
    ) : HarnessEvent

    /** Engine validated/authorized the request; execution will begin. */
    data class ToolCallAccepted(
        override val requestId: String,
        override val pluginId: String,
        override val toolName: String,
    ) : ToolCallEvent

    data class ToolCallRejected(
        override val requestId: String,
        override val pluginId: String,
        override val toolName: String,
        val reason: ToolRejectionReason,
    ) : ToolCallEvent

    /** Emitted immediately before plugin side-effect dispatch. */
    data class ToolCallStarted(
        override val requestId: String,
        override val pluginId: String,
        override val toolName: String,
    ) : ToolCallEvent

    data class ToolCallCompleted(
        override val requestId: String,
        override val pluginId: String,
        override val toolName: String,
        val result: ToolResult,
    ) : ToolCallEvent

    data class ToolCallFailed(
        override val requestId: String,
        override val pluginId: String,
        override val toolName: String,
        val error: HarnessError,
    ) : ToolCallEvent
}

sealed interface ToolCallEvent : HarnessEvent {
    val requestId: String
    val pluginId: String
    val toolName: String
}

/**
 * Non-replaying visual commands. `PlayAnimation` does not carry request state;
 * it is fire-and-forget visual feedback and must never be treated as a
 * success signal for a tool call.
 */
sealed interface VisualCommand : HarnessEvent {
    data class PlayAnimation(val animation: String) : VisualCommand
    data class Say(val text: String) : VisualCommand
}