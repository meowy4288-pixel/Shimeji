package dev.delpa.shimeji.core.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Tool contracts shared by the harness and plugins.
 * These serialize cleanly to JSON for the future model-facing catalog.
 */

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val permission: ToolPermission = ToolPermission.NONE,
    val impacts: List<ToolImpact> = emptyList(),
    /** Whether this tool needs a physical capability that may be absent (sensor, battery access...). */
    val requiresCapability: String? = null,
)

enum class ToolPermission {
    /** No user confirmation required. */
    NONE,
    /** Confirmation must bind to tool + exact arguments and expire. */
    CONFIRMATION_REQUIRED,
}

@Serializable
enum class ToolImpact {
    READ_ONLY,
    WRITES_LOCAL_STATE,
    MODIFIES_SYSTEM,
    OPENS_EXTERNAL_APP,
}

@Serializable
sealed interface ToolResult {
    val ok: Boolean
}

@Serializable
data class SuccessResult(
    override val ok: Boolean = true,
    val data: JsonObject = JsonObject(emptyMap()),
    /** May be omitted; logs/JSON keep sensitive args out by default. */
    val summary: String = "",
) : ToolResult

@Serializable
data class FailureResult(
    val code: String,
    val message: String = "",
    override val ok: Boolean = false,
) : ToolResult

/**
 * Typed result for confirmed-required calls. For this milestone confirmation-
 * required calls return this typed result rather than launching a background
 * dialog. The future UI layer may bind user confirmation to the exact
 * [requestId] + tool args and re-submit via the engine API.
 */
@Serializable
data class ConfirmationRequired(
    val requestId: String,
    val pluginId: String,
    val toolName: String,
    val paramsJson: String,
    val summary: String = "User confirmation required",
) : ToolResult {
    override val ok: Boolean = false
}

/**
 * Structured error from the harness. Typed, JSON-serializable, never a crash.
 */
@Serializable
data class HarnessError(
    val code: ErrorCode,
    val message: String,
    val pluginId: String? = null,
    val toolName: String? = null,
    val requestId: String? = null,
)

@Serializable
enum class ErrorCode {
    UNKNOWN_TOOL,
    PLUGIN_NOT_ENABLED,
    PLUGIN_DISABLING,
    PLUGIN_FAILED,
    MALFORMED_ARGUMENTS,
    INVALID_ARGUMENTS,
    PERMISSION_REQUIRED,
    MISSING_CAPABILITY,
    TOOL_BUSY,
    TIMEOUT,
    CANCELLED,
    DUPLICATE_REQUEST_ID,
    PLUGIN_ERROR,
    REGISTRATION_ERROR,
    SHUTTING_DOWN,
    NOT_STARTED,
}

/** Structured rejection reason for ToolCallRejected events. */
@Serializable
data class ToolRejectionReason(
    val code: ErrorCode,
    val message: String,
)

/**
 * Catalog snapshot handed to a model adapter. No provider-specific formatting.
 */
@Serializable
data class ToolCatalog(
    val revision: Long,
    val tools: List<ToolDefinition>,
    val unavailableToolSignatures: List<UnavailableTool>,
)

/**
 * A tool that exists on a *registered or enabled* plugin but cannot currently
 * be called because the plugin is disabled, a permission is missing, or a
 * physical capability is absent. Distinguishes "plugin enabled but tool
 * unavailable" from "plugin disabled".
 */
@Serializable
data class UnavailableTool(
    val pluginId: String,
    val toolName: String,
    val reason: String,
)

/** JSON Schema subset supported by [dev.delpa.shimeji.core.harness.SchemaValidator]. */
object SchemaKeywords {
    const val TYPE = "type"
    const val PROPERTIES = "properties"
    const val REQUIRED = "required"
    const val ITEMS = "items"
    const val MINIMUM = "minimum"
    const val MAXIMUM = "maximum"
    const val ENUM = "enum"
    const val MIN_LENGTH = "minLength"
    const val MAX_LENGTH = "maxLength"
    const val PATTERN = "pattern"
    const val DESCRIPTION = "description"
}

/** Canonical tool-name namespace examples (device.*, mock.*, visual.*). */
object ToolNames {
    const val READ_BATTERY = "device.read_battery"
    const val MOCK_ECHO = "mock.echo"
    const val MOCK_IMAGINE = "mock.imagine"
}

/** Builds request IDs; deterministic in tests via injected function. */
fun interface RequestIdFactory {
    fun next(): String
}

object DefaultRequestIdFactory : RequestIdFactory {
    private val counter = java.util.concurrent.atomic.AtomicLong(0L)
    override fun next(): String = "req-${System.nanoTime()}-${counter.incrementAndGet()}"
}