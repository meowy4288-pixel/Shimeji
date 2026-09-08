package dev.delpa.shimeji.core.plugin.device

import dev.delpa.shimeji.core.harness.ErrorCode
import dev.delpa.shimeji.core.harness.FailureResult
import dev.delpa.shimeji.core.harness.SuccessResult
import dev.delpa.shimeji.core.harness.ToolDefinition
import dev.delpa.shimeji.core.harness.ToolImpact
import dev.delpa.shimeji.core.harness.ToolNames
import dev.delpa.shimeji.core.harness.ToolPermission
import dev.delpa.shimeji.core.harness.ToolResult
import dev.delpa.shimeji.core.plugin.PluginCategory
import dev.delpa.shimeji.core.plugin.PluginContext
import dev.delpa.shimeji.core.plugin.PluginMetadata
import dev.delpa.shimeji.core.plugin.ShimejiPlugin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Read-only device plugin. Initial milestone exposes exactly one real local
 * read (battery level) and no high-impact actions.
 */
class DeviceControlPlugin(
    private val batteryProvider: suspend () -> Int?,
) : ShimejiPlugin {

    override val metadata = PluginMetadata(
        id = "device",
        name = "Device Control",
        version = "1.0.0",
        category = PluginCategory.DEVICE,
        description = "Read-only device information (battery). No high-impact actions in this milestone.",
    )

    override fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = ToolNames.READ_BATTERY,
            description = "Read the current battery level as a percent (0-100).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
                put("required", kotlinx.serialization.json.buildJsonArray { })
            },
            permission = ToolPermission.NONE,
            impacts = listOf(ToolImpact.READ_ONLY),
            requiresCapability = "battery-access",
        ),
    )

    override suspend fun onEnable(context: PluginContext) {
        // No-op: battery reads are stateless.
    }

    override suspend fun onDisable() {
        // No-op.
    }

    override suspend fun executeTool(name: String, params: JsonObject): ToolResult = when (name) {
        ToolNames.READ_BATTERY -> {
            // Real local read — not fabricated.
            val level: Int? = batteryProvider()
            if (level == null) {
                FailureResult(
                    code = ErrorCode.MISSING_CAPABILITY.name,
                    message = "Battery permission not granted or battery level unavailable",
                )
            } else {
                SuccessResult(
                    data = buildJsonObject {
                        put("level_percent", level)
                        putJsonObject("source") { put("real", true) }
                    },
                    summary = "Battery at $level%",
                )
            }
        }
        else -> FailureResult(
            code = ErrorCode.UNKNOWN_TOOL.name,
            message = "Unknown tool: $name",
        )
    }
}