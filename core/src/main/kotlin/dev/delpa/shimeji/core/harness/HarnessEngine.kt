package dev.delpa.shimeji.core.harness

import dev.delpa.shimeji.core.event.EventBus
import dev.delpa.shimeji.core.event.HarnessEvent
import dev.delpa.shimeji.core.event.ToolCallEvent
import dev.delpa.shimeji.core.plugin.PluginContext
import dev.delpa.shimeji.core.plugin.PluginStateSnapshot
import dev.delpa.shimeji.core.plugin.PluginStatus
import dev.delpa.shimeji.core.plugin.ShimejiPlugin
import dev.delpa.shimeji.core.plugin.SystemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * The model-neutral pipeline:
 *   model proposal -> [HarnessEngine.submitToolCall] (validation + authorization)
 *   -> configured dispatcher (plugin execution, serialized per plugin)
 *   -> result flows back to [HarnessEngine.requestResults] and the EventBus.
 *
 * Ownership rules:
 *  - The engine is the ONLY dispatcher of tools. UI/collectors may *request*,
 *    never execute.
 *  - [start] must be called once by the composition root; the engine is the
 *    shared instance owned by the Application (never re-created by Activity or
 *    Service independently).
 *  - [shutdown] cancels plugin work and rejects all further submissions.
 */
class HarnessEngine(
    private val pluginContextFactory: (ShimejiPlugin) -> PluginContext,
    private val defaultTimeoutMillis: Long = 15_000L,
    private val dupHistorySize: Int = 256,
    private val requestIdFactory: RequestIdFactory = DefaultRequestIdFactory,
) {
    private data class Entry(
        val plugin: ShimejiPlugin,
        val state: MutableStateFlow<PluginStateSnapshot>,
        val executionMutex: Mutex = Mutex(),
        var toolDefinitions: List<ToolDefinition> = emptyList(),
    )

    private val _eventBus: EventBus = object : EventBus {
        private val flow = MutableSharedFlow<HarnessEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<HarnessEvent> = flow.asSharedFlow()
        override fun emit(event: HarnessEvent): Boolean = flow.tryEmit(event)
    }

    private val lock = Mutex()
    private val registry = LinkedHashMap<String, Entry>()
    private val recentRequestIds = ArrayDeque<String>()
    private val recentIdsSet = HashSet<String>() // bounded by dupHistorySize
    private val catalogRevision = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pluginStates = MutableStateFlow<List<PluginStateSnapshot>>(emptyList())
    val pluginStates: StateFlow<List<PluginStateSnapshot>> = _pluginStates.asStateFlow()

    private val _catalog = MutableStateFlow(ToolCatalog(0L, emptyList(), emptyList()))
    val catalog: StateFlow<ToolCatalog> = _catalog.asStateFlow()

    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()

    private val _toolResults = MutableSharedFlow<ToolResult>(extraBufferCapacity = 64)
    /** Results keyed by request id; also observable as a plain flow. */
    val requestResults: SharedFlow<ToolResult> = _toolResults.asSharedFlow()

    val events: SharedFlow<HarnessEvent> get() = _eventBus.events

    init {
        _pluginStates.value = snapshotStates()
    }

    // ------------------------------------------------------------------
    // Registry / lifecycle
    // ------------------------------------------------------------------

    /** Register a plugin so it is known; does NOT enable it. */
    suspend fun register(plugin: ShimejiPlugin) = lock.withLock {
        if (registry.containsKey(plugin.metadata.id)) {
            throw HarnessRegistrationException(
                ErrorCode.REGISTRATION_ERROR,
                "Duplicate plugin id: ${plugin.metadata.id}",
            )
        }
        val entry = Entry(plugin, MutableStateFlow(snapshot(plugin, PluginStatus.DISABLED)))
        entry.toolDefinitions = plugin.getToolDefinitions()
        // Validate no duplicate canonical tool names across *known* plugins.
        val known = registry.values.flatMap { it.toolDefinitions }.map { it.name }.toSet()
        val dup = entry.toolDefinitions.map { it.name }.firstOrNull { it in known }
        if (dup != null) {
            throw HarnessRegistrationException(
                ErrorCode.REGISTRATION_ERROR,
                "Ambiguous tool registration: $dup",
            )
        }
        registry[plugin.metadata.id] = entry
        refreshStatesLocked()
        refreshCatalogLocked()
    }

    suspend fun unregister(pluginId: String) = lock.withLock {
        registry.remove(pluginId) ?: return
        refreshStatesLocked()
        refreshCatalogLocked()
    }

    /** Enable a plugin. Idempotent; plugin-owned work is cancelled via its own scope later. */
    suspend fun enable(pluginId: String) {
        val entry = lock.withLock { registry[pluginId] ?: return }.also {
            ensureStarted()
        }
        // Lifecycle transition is serialized per plugin.
        entry.executionMutex.withLock {
            val current = entry.state.value.status
            if (current == PluginStatus.ENABLED) return
            if (current == PluginStatus.ENABLING || current == PluginStatus.DISABLING) return
            entry.state.update { it.copy(status = PluginStatus.ENABLING, errorMessage = null) }
            refreshStatesLockedNoLock()
            try {
                val ctx = pluginContextFactory(entry.plugin)
                entry.plugin.onEnable(ctx)
                entry.state.update { it.copy(status = PluginStatus.ENABLED, enabled = true) }
            } catch (t: Throwable) {
                // Failed-enable rollback: caller of onEnable owns cleanup;
                // plugin.onEnable must not leave partial state. We mark FAILED.
                entry.state.update { it.copy(status = PluginStatus.FAILED, enabled = false, errorMessage = t.message ?: "enable failed") }
            }
            refreshCatalogLockedNoLock()
            refreshStatesLockedNoLock()
        }
    }

    /** Disable a plugin. Rejects new calls as soon as disable begins. */
    suspend fun disable(pluginId: String) {
        val entry = lock.withLock { registry[pluginId] ?: return }.also { ensureStarted() }
        entry.executionMutex.withLock {
            val current = entry.state.value.status
            if (current == PluginStatus.DISABLED || current == PluginStatus.DISABLING) return
            entry.state.update { it.copy(status = PluginStatus.DISABLING, enabled = false) }
            refreshStatesLockedNoLock()
            refreshCatalogLockedNoLock() // remove tools immediately: reject new calls during disable
            try {
                entry.plugin.onDisable()
                entry.state.update { it.copy(status = PluginStatus.DISABLED) }
            } catch (t: Throwable) {
                entry.state.update { it.copy(status = PluginStatus.DISABLED, errorMessage = t.message ?: "disable cleanup failed") }
            }
            refreshStatesLockedNoLock()
        }
    }

    /**
     * Immediate-disable (used during shutdown): marks DISABLED synchronously so
     * the registry lock is not held during plugin lifecycle calls.
     */
    private suspend fun immediateDisable(entry: Entry, reason: String) {
        entry.state.update { it.copy(status = PluginStatus.DISABLED, enabled = false, errorMessage = reason) }
    }

    // ------------------------------------------------------------------
    // Tool calls
    // ------------------------------------------------------------------

    data class ToolCallRequest(
        val pluginId: String,
        val toolName: String,
        val params: JsonObject,
        val timeoutMillis: Long? = null,
        val requestId: String? = null,
    )

    /**
     * Entry point for a model-agent submission. Returns the tool result.
     * Handles startup/shutdown rejection, duplicate request IDs, validation,
     * authorization, per-plugin serialization, cooperative cancellation and
     * bounded timeouts. Never auto-retries.
     */
    suspend fun submitToolCall(request: ToolCallRequest): ToolResult {
        val requestId = request.requestId ?: requestIdFactory.next()
        val toolName = request.toolName
        val pluginId = request.pluginId

        // Shutdown gate takes precedence so a stopped engine reports SHUTTING_DOWN.
        if (shuttingDown.get()) {
            val err = HarnessError(ErrorCode.SHUTTING_DOWN, "Harness is shutting down")
            publish(requestId, pluginId, toolName, err, EventPhase.REJECTED, request.params)
            return FailureResult(ErrorCode.SHUTTING_DOWN.name, "Harness is shutting down")
        }
        // Startup gate. No silent loss.
        if (!_started.value) {
            val err = HarnessError(ErrorCode.NOT_STARTED, "Harness is not started")
            publish(requestId, pluginId, toolName, err, EventPhase.REJECTED, request.params)
            return FailureResult(ErrorCode.NOT_STARTED.name, "Harness is not started")
        }

        // Duplicate request-id guard (bounded in-process history).
        if (!rememberRequestId(requestId)) {
            val err = HarnessError(ErrorCode.DUPLICATE_REQUEST_ID, "Duplicate request id: $requestId")
            publish(requestId, pluginId, toolName, err, EventPhase.REJECTED, request.params)
            return FailureResult(ErrorCode.DUPLICATE_REQUEST_ID.name, "Duplicate request id")
        }

        publish(requestId, pluginId, toolName, null, EventPhase.REQUESTED, request.params)

        // Validation under lock but WITHOUT holding it during execution.
        val validation: Validation = lock.withLock {
            ensureStarted()
            validateRequestLocked(requestId, pluginId, toolName, request.params)
        }

        if (validation is Validation.Rejected) {
            val err = validation.error
            publish(requestId, pluginId, toolName, err, EventPhase.REJECTED, request.params)
            return FailureResult(err.code.name, err.message)
        }
        if (validation is Validation.ConfirmationBlocked) {
            val c = ConfirmationRequired(
                requestId = requestId,
                pluginId = pluginId,
                toolName = toolName,
                paramsJson = kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), request.params),
            )
            _toolResults.tryEmit(c)
            publish(requestId, pluginId, toolName, null, EventPhase.ACCEPTED, request.params)
            publish(requestId, pluginId, toolName, c, EventPhase.COMPLETED, request.params)
            return c
        }

        val accepted = validation as Validation.Accepted
        val timeoutMs = request.timeoutMillis ?: defaultTimeoutMillis

        publish(requestId, pluginId, toolName, null, EventPhase.ACCEPTED, request.params)
        // Visual feedback fires right after acceptance, before execution even
        // begins, and never blocks or implies success (result events are separate).
        _eventBus.emit(dev.delpa.shimeji.core.event.VisualCommand.PlayAnimation("magic_cast"))
        // "Execution start" is distinct from acceptance.
        publish(requestId, pluginId, toolName, null, EventPhase.STARTED, request.params)

        return accepted.entry.executionMutex.withLock {
            // Revalidate immediately before execution: may have changed since validation.
            val now = lock.withLock { validateRequestLocked(requestId, pluginId, toolName, request.params) }
            if (now !is Validation.Accepted) {
                val err = (now as? Validation.Rejected)?.error
                    ?: HarnessError(ErrorCode.PLUGIN_DISABLING, "Target changed before execution")
                val res = FailureResult(err.code.name, err.message)
                _toolResults.tryEmit(res)
                publish(requestId, pluginId, toolName, err, EventPhase.FAILED, request.params)
                res
            } else {
                executeAndComplete(requestId, pluginId, toolName, request.params, timeoutMs, accepted.entry)
            }
        }
    }

    private suspend fun executeAndComplete(
        requestId: String,
        pluginId: String,
        toolName: String,
        params: JsonObject,
        timeoutMs: Long,
        entry: Entry,
    ): ToolResult {
        val plugin = entry.plugin
        // Cooperative cancellation: the caller cancelling the job cancels this
        // coroutine; the plugin sees its own CancellationException. A completed
        // external side effect cannot be undone by cancellation.
        val result: ToolResult = try {
            withTimeout(timeoutMs) {
                plugin.executeTool(toolName, params)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            FailureResult(ErrorCode.TIMEOUT.name, "Tool timed out after ${timeoutMs}ms")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            FailureResult(ErrorCode.PLUGIN_ERROR.name, t.message ?: "Plugin threw ${t::class.simpleName}")
        }
        _toolResults.tryEmit(result)
        if (result is FailureResult) {
            val code = runCatching { ErrorCode.valueOf(result.code) }.getOrDefault(ErrorCode.PLUGIN_ERROR)
            publish(
                requestId,
                pluginId,
                toolName,
                HarnessError(code, result.message, pluginId, toolName, requestId),
                EventPhase.FAILED,
                params,
            )
        } else {
            publish(requestId, pluginId, toolName, result, EventPhase.COMPLETED, params)
        }
        return result
    }

    private object EventPhase {
        const val REQUESTED = 0
        const val ACCEPTED = 1
        const val STARTED = 2
        const val COMPLETED = 3
        const val FAILED = 4
        const val REJECTED = 5
    }

    private fun publish(
        requestId: String,
        pluginId: String,
        toolName: String,
        resultOrError: Any?,
        phase: Int,
        paramsForRequested: JsonObject,
    ) {
        val event: HarnessEvent = when (phase) {
            EventPhase.REQUESTED -> HarnessEvent.ToolCallRequested(requestId, pluginId, toolName, paramsForRequested)
            EventPhase.ACCEPTED -> HarnessEvent.ToolCallAccepted(requestId, pluginId, toolName)
            EventPhase.STARTED -> HarnessEvent.ToolCallStarted(requestId, pluginId, toolName)
            EventPhase.COMPLETED -> HarnessEvent.ToolCallCompleted(requestId, pluginId, toolName, resultOrError as ToolResult)
            EventPhase.FAILED -> HarnessEvent.ToolCallFailed(requestId, pluginId, toolName, resultOrError as HarnessError)
            EventPhase.REJECTED -> HarnessEvent.ToolCallRejected(
                requestId,
                pluginId,
                toolName,
                ToolRejectionReason((resultOrError as HarnessError).code, (resultOrError as HarnessError).message),
            )
            else -> return
        }
        _eventBus.emit(event)
    }

    /** One execution per accepted request: callers that re-submit get a DUPLICATE id failure. */
    private fun rememberRequestId(requestId: String): Boolean = synchronized(recentIdsSet) {
        if (requestId in recentIdsSet) return false
        recentRequestIds.addLast(requestId)
        recentIdsSet.add(requestId)
        while (recentRequestIds.size > dupHistorySize) {
            val removed = recentRequestIds.removeFirst()
            recentIdsSet.remove(removed)
        }
        true
    }

    private sealed interface Validation {
        data class Accepted(val entry: Entry) : Validation
        data class Rejected(val error: HarnessError) : Validation
        data object ConfirmationBlocked : Validation
    }

    private suspend fun validateRequestLocked(
        requestId: String,
        pluginId: String,
        toolName: String,
        params: JsonObject,
    ): Validation {
        val entry = registry[pluginId] ?: return Validation.Rejected(
            HarnessError(ErrorCode.UNKNOWN_TOOL, "Unknown plugin: $pluginId", pluginId, toolName, requestId),
        )
        val status = entry.state.value.status
        when (status) {
            PluginStatus.DISABLED, PluginStatus.FAILED -> return Validation.Rejected(
                HarnessError(ErrorCode.PLUGIN_NOT_ENABLED, "Plugin is $status", pluginId, toolName, requestId),
            )
            PluginStatus.ENABLING -> return Validation.Rejected(
                HarnessError(ErrorCode.PLUGIN_NOT_ENABLED, "Plugin is still enabling", pluginId, toolName, requestId),
            )
            PluginStatus.DISABLING -> return Validation.Rejected(
                HarnessError(ErrorCode.PLUGIN_DISABLING, "Plugin is disabling; new calls rejected", pluginId, toolName, requestId),
            )
            PluginStatus.ENABLED -> Unit
        }
        val def = entry.toolDefinitions.firstOrNull { it.name == toolName }
            ?: return Validation.Rejected(
                HarnessError(ErrorCode.UNKNOWN_TOOL, "Unknown tool: $toolName on $pluginId", pluginId, toolName, requestId),
            )

        // Schema validation (arguments are untrusted).
        val errors = SchemaValidator.validateObjectParams(params, def.inputSchema)
        if (errors.isNotEmpty()) {
            val msg = errors.joinToString("; ") { describe(it) }
            return Validation.Rejected(HarnessError(ErrorCode.MALFORMED_ARGUMENTS, msg, pluginId, toolName, requestId))
        }

        // Authorization / permission policy.
        when (def.permission) {
            ToolPermission.NONE -> Unit
            ToolPermission.CONFIRMATION_REQUIRED -> return Validation.ConfirmationBlocked
        }

        // Capability check (e.g. battery permission).
        def.requiresCapability?.let { cap ->
            if (!capabilityAvailable(entry.plugin, cap)) {
                return Validation.Rejected(
                    HarnessError(ErrorCode.MISSING_CAPABILITY, "Missing capability: $cap", pluginId, toolName, requestId),
                )
            }
        }
        return Validation.Accepted(entry)
    }

    private fun capabilityAvailable(plugin: ShimejiPlugin, capability: String): Boolean {
        return try {
            pluginContextFactory(plugin).systemInfo.let { si ->
                when (capability) {
                    "battery-access" -> si.batteryLevelPercent != null
                    // Allow future named capabilities to be added here.
                    else -> true
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun describe(e: SchemaValidator.ValidationError): String = when (e) {
        is SchemaValidator.ValidationError.MissingProperty -> "missing '${e.property}' at ${e.path}"
        is SchemaValidator.ValidationError.TypeMismatch -> "type mismatch at ${e.path}: expected ${e.expected}, got ${e.actual}"
        is SchemaValidator.ValidationError.OutOfRange -> "out of range at ${e.path}: ${e.message}"
        is SchemaValidator.ValidationError.NotInEnum -> "value not in enum at ${e.path}"
        is SchemaValidator.ValidationError.BadString -> "bad string at ${e.path}: ${e.message}"
    }

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    private suspend fun refreshCatalogLocked() = refreshCatalogLockedNoLock()

    private suspend fun refreshCatalogLockedNoLock() {
        val revision = catalogRevision.incrementAndGet()
        val tools = mutableListOf<ToolDefinition>()
        val unavailable = mutableListOf<UnavailableTool>()
        for (e in registry.values) {
            val st = e.state.value.status
            for (def in e.toolDefinitions) {
                if (st == PluginStatus.ENABLED) {
                    tools += def
                    if (def.requiresCapability != null && !capabilityAvailable(e.plugin, def.requiresCapability)) {
                        unavailable += UnavailableTool(e.plugin.metadata.id, def.name, "missing capability ${def.requiresCapability}")
                    }
                } else {
                    unavailable += UnavailableTool(e.plugin.metadata.id, def.name, "plugin is $st")
                }
            }
        }
        _catalog.value = ToolCatalog(revision, tools, unavailable)
    }

    private fun refreshStatesLocked() {
        _pluginStates.value = snapshotStates()
    }

    private suspend fun refreshStatesLockedNoLock() {
        _pluginStates.value = snapshotStates()
    }

    private fun snapshotStates(): List<PluginStateSnapshot> =
        registry.values.map { it.state.value }

    private fun snapshot(plugin: ShimejiPlugin, status: PluginStatus): PluginStateSnapshot =
        PluginStateSnapshot(plugin.metadata.id, plugin.metadata.name, status, status == PluginStatus.ENABLED)

    // ------------------------------------------------------------------
    // Lifecycle of the engine itself
    // ------------------------------------------------------------------

    private val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        _started.value = true
    }

    /**
     * Idempotent shutdown. Marks DISABLED for every plugin (no lifecycle code
     * under the registry lock), cancels plugin-owned work, and rejects new calls.
     */
    suspend fun shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            lock.withLock {
                registry.values.forEach { immediateDisable(it, "engine shutdown") }
                refreshCatalogLockedNoLock()
                refreshStatesLockedNoLock()
                _started.value = false
            }
            scope.cancel()
        }
    }

    private fun ensureStarted() {
        if (!_started.value) {
            throw HarnessNotStartedException()
        }
    }

    /** Convenience for tests: submit via a mock agent through [ModelAgentAdapter]. */
    suspend fun submitFromAgent(
        pluginId: String,
        toolName: String,
        params: JsonObject,
        requestId: String? = null,
        timeoutMillis: Long? = null,
    ): ToolResult {
        return submitToolCall(ToolCallRequest(pluginId, toolName, params, timeoutMillis, requestId))
    }

    fun emitVisualCommand(command: dev.delpa.shimeji.core.event.VisualCommand) {
        _eventBus.emit(command)
    }

    companion object {
        val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

class HarnessRegistrationException(val code: ErrorCode, message: String) : IllegalArgumentException(message)
class HarnessNotStartedException : IllegalStateException("Harness not started")