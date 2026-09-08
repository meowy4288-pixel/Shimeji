package dev.delpa.shimeji.core.model

import dev.delpa.shimeji.core.harness.ToolCatalog
import dev.delpa.shimeji.core.harness.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Model-neutral adapter boundary. A future on-device tool-calling model plugs
 * in here; this milestone ships only [MockAgentPlugin].
 *
 * Do NOT invent an SDK, inference API, tokenizer format, chat template, or
 * function-calling format here. Real inference stays behind an explicitly
 * unimplemented adapter until its actual runtime and documentation are known.
 */
interface ModelAdapter {
    val name: String

    /** Snapshot of currently callable tools (the engine's active catalog). */
    fun catalogSnapshot(): ToolCatalog

    /**
     * Propose structured tool calls against the catalog. Returns finished or
     * pending requests; this milestone's adapter resolves deterministically.
     * The adapter proposes — the harness authorizes — never the reverse.
     */
    fun proposeCalls(requestDescription: String): List<ProposedCall>

    /**
     * Stream of results for previously proposed calls (requestId-keyed).
     * Returns Finished for deterministic adapters or NotReady while pending.
     */
    fun awaitResult(requestId: String): ResultResolution

    /** Whether the user trigger must be used (mock agent) — never auto-runs. */
    val requiresManualTrigger: Boolean

    /** The adapter can NEVER approve its own confirmation-required requests. */
    val canSelfApprove: Boolean get() = false
}

data class ProposedCall(
    val requestId: String,
    val pluginId: String,
    val toolName: String,
    val params: JsonObject,
)

sealed interface ResultResolution {
    data class Resolved(val requestId: String, val result: ToolResult) : ResultResolution
    data class NotReady(val requestId: String) : ResultResolution
}

/**
 * Placeholder for the real on-device model. Intentionally unimplemented:
 * throwing here makes the integration point explicit rather than fake.
 */
class UnimplementedLocalModelAdapter : ModelAdapter {
    override val name: String = "unimplemented-local-model"
    private val token = IllegalStateException(
        "Real inference adapter is not implemented: no model runtime, SDK, or " +
            "format has been chosen for this milestone. Replace this class, not the interface.",
    )

    override fun catalogSnapshot(): ToolCatalog = throw token
    override fun proposeCalls(requestDescription: String): List<ProposedCall> = throw token
    override fun awaitResult(requestId: String): ResultResolution = throw token
    override val requiresManualTrigger: Boolean = true
}