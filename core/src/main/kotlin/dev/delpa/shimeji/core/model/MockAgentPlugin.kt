package dev.delpa.shimeji.core.model

import dev.delpa.shimeji.core.harness.HarnessEngine
import dev.delpa.shimeji.core.harness.ToolCatalog
import dev.delpa.shimeji.core.harness.ToolNames
import dev.delpa.shimeji.core.harness.ToolResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic in-process "model" used to demo the pipeline. It proposes one
 * usable side-effect-free call. It cannot authorize anything.
 */
class MockAgentPlugin(
    private val engine: HarnessEngine,
    override val name: String = "mock-agent",
) : ModelAdapter {

    private val results = MutableSharedFlow<ToolResult>(extraBufferCapacity = 8)

    /** If the device battery tool is absent from the active catalog, propose the echo tool instead. */
    override fun proposeCalls(requestDescription: String): List<ProposedCall> {
        val catalog = engine.catalog.value
        val battery = catalog.tools.firstOrNull { it.name == ToolNames.READ_BATTERY }
        return if (battery != null) {
            listOf(
                ProposedCall(
                    requestId = "mock-${System.nanoTime()}",
                    pluginId = battery.name.substringBefore("."),
                    toolName = battery.name,
                    params = buildJsonObject {},
                ),
            )
        } else {
            // Fall back to the mock plugin's echo so the demo still exercises the pipeline.
            val echo = catalog.tools.firstOrNull { it.name == ToolNames.MOCK_ECHO }
            if (echo != null) {
                listOf(
                    ProposedCall(
                        requestId = "mock-${System.nanoTime()}",
                        pluginId = "mock",
                        toolName = ToolNames.MOCK_ECHO,
                        params = buildJsonObject { put("text", "hello from the mock agent") },
                    ),
                )
            } else emptyList()
        }
    }

    /** The engine already emits every result; expose a convenience stream. */
    suspend fun submitAndCollect(proposal: ProposedCall): ToolResult {
        val result = engine.submitToolCall(
            HarnessEngine.ToolCallRequest(proposal.pluginId, proposal.toolName, proposal.params, requestId = proposal.requestId),
        )
        results.emit(result)
        return result
    }

    override fun catalogSnapshot(): ToolCatalog = engine.catalog.value

    override fun awaitResult(requestId: String): ResultResolution {
        // Deterministic adapters resolve synchronously through submitAndCollect;
        // for standalone use we surface NotReady (results flow through the engine).
        return ResultResolution.NotReady(requestId)
    }

    override val requiresManualTrigger: Boolean = true
    override val canSelfApprove: Boolean = false

    val resultFlow: SharedFlow<ToolResult> = results.asSharedFlow()
}