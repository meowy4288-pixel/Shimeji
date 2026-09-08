package dev.delpa.shimeji.app

import dev.delpa.shimeji.core.plugin.PluginCategory
import dev.delpa.shimeji.core.plugin.PluginContext
import dev.delpa.shimeji.core.plugin.PluginMetadata
import dev.delpa.shimeji.core.plugin.ShimejiPlugin
import dev.delpa.shimeji.core.harness.ToolDefinition
import dev.delpa.shimeji.core.harness.ToolResult
import dev.delpa.shimeji.core.harness.SuccessResult
import kotlinx.serialization.json.JsonObject

/**
 * Visual plugin: exists to demonstrate that visual/model plugins may expose an
 * empty tool list and still be enabled/disabled at runtime. Rendering itself
 * is owned by the overlay service; this plugin is intentionally a no-op shell.
 */
class DemoVisualPlugin : ShimejiPlugin {
    override val metadata = PluginMetadata(
        id = "visual",
        name = "Visual Feedback",
        version = "1.0.0",
        category = PluginCategory.VISUAL,
        description = "Demonstration visual plugin (empty tool list). Replace with real artwork/effects.",
    )

    override fun getToolDefinitions(): List<ToolDefinition> = emptyList()

    override suspend fun onEnable(context: PluginContext) = Unit
    override suspend fun onDisable() = Unit

    override suspend fun executeTool(name: String, params: JsonObject): ToolResult =
        SuccessResult(summary = "No tools on visual plugin")
}