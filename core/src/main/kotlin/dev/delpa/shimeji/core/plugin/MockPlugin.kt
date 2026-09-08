package dev.delpa.shimeji.core.plugin

import dev.delpa.shimeji.core.harness.FailureResult
import dev.delpa.shimeji.core.harness.ErrorCode
import dev.delpa.shimeji.core.harness.SuccessResult
import dev.delpa.shimeji.core.harness.ToolDefinition
import dev.delpa.shimeji.core.harness.ToolImpact
import dev.delpa.shimeji.core.harness.ToolNames
import dev.delpa.shimeji.core.harness.ToolPermission
import dev.delpa.shimeji.core.harness.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Generic plugin used by tests and by the app's demo catalog as the mock
 * agent's fallback target. Exposes a single side-effect-free echo tool.
 */
class MockPlugin : ShimejiPlugin {
    override val metadata = PluginMetadata(
        id = "mock",
        name = "Mock Plugin",
        version = "1.0.0",
        category = PluginCategory.MODEL,
        description = "Demo plugin exposing a side-effect-free echo tool.",
    )

    override fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = ToolNames.MOCK_ECHO,
            description = "Echo back the provided text (side-effect free).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", 200)
                    }
                }
                put("required", buildJsonArray { add("text") })
            },
            permission = ToolPermission.NONE,
            impacts = listOf(ToolImpact.READ_ONLY),
        ),
    )

    override suspend fun onEnable(context: PluginContext) = Unit
    override suspend fun onDisable() = Unit

    override suspend fun executeTool(name: String, params: JsonObject): ToolResult = when (name) {
        ToolNames.MOCK_ECHO -> {
            val text = (params["text"] as? JsonPrimitive)?.content ?: ""
            SuccessResult(
                data = buildJsonObject { put("received", text) },
                summary = "Echo: $text",
            )
        }
        else -> FailureResult(ErrorCode.UNKNOWN_TOOL.name, "Unknown tool: $name")
    }
}