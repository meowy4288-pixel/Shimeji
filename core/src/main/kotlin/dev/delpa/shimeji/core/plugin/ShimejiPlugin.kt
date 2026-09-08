package dev.delpa.shimeji.core.plugin

import dev.delpa.shimeji.core.harness.ToolDefinition
import dev.delpa.shimeji.core.harness.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A trusted, compiled-in plugin module.
 *
 * This milestone treats plugins strictly as in-process, statically compiled
 * modules. There is no APK/DEX download, no script execution, no separate
 * process, and no MCP protocol server.
 */
interface ShimejiPlugin {
    val metadata: PluginMetadata
    fun getToolDefinitions(): List<ToolDefinition>
    suspend fun onEnable(context: PluginContext)
    suspend fun onDisable()
    suspend fun executeTool(name: String, params: JsonObject): ToolResult
}

@Serializable
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val category: PluginCategory,
    val description: String = "",
)

enum class PluginCategory {
    DEVICE,
    MODEL,
    VISUAL,
    SYSTEM,
}

/** Narrow plugin-facing context. No Activity, no unrestricted service locator. */
interface PluginContext {
    val pluginId: String
    val eventBus: dev.delpa.shimeji.core.event.EventBus
    val systemInfo: SystemInfo

    /** Read-only access to bundled JSON assets (e.g. the FSM config). */
    suspend fun loadAsset(assetName: String): String
}

interface SystemInfo {
    val batteryLevelPercent: Int?
    val hasBatteryPermission: Boolean
}

@Serializable
data class PluginStateSnapshot(
    val pluginId: String,
    val pluginName: String,
    val status: PluginStatus,
    val enabled: Boolean,
    val errorMessage: String? = null,
)

enum class PluginStatus {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING,
    FAILED,
}