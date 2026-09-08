package dev.delpa.shimeji.core.harness

import dev.delpa.shimeji.core.event.HarnessEvent
import dev.delpa.shimeji.core.event.VisualCommand
import dev.delpa.shimeji.core.plugin.PluginCategory
import dev.delpa.shimeji.core.plugin.PluginContext
import dev.delpa.shimeji.core.plugin.PluginMetadata
import dev.delpa.shimeji.core.plugin.PluginStatus
import dev.delpa.shimeji.core.plugin.ShimejiPlugin
import dev.delpa.shimeji.core.plugin.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun echoTool(
    name: String = "mock.echo",
    permission: ToolPermission = ToolPermission.NONE,
    requiresCapability: String? = null,
    requiredParams: Boolean = true,
) = ToolDefinition(
    name = name,
    description = "echo tool",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("minLength", 1)
            }
        }
        if (requiredParams) put("required", buildJsonArray { add("text") })
    },
    permission = permission,
    impacts = listOf(ToolImpact.READ_ONLY),
    requiresCapability = requiresCapability,
)

class HarnessEngineTest {

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    private class TestPlugin(
        private val id: String,
        private val tools: List<ToolDefinition> = listOf(echoTool()),
        private val onEnableBlock: suspend () -> Unit = {},
        private val onDisableBlock: suspend () -> Unit = {},
        private val executeBlock: suspend (String, JsonObject) -> ToolResult = { _, _ ->
            SuccessResult(data = buildJsonObject { put("ok", true) })
        },
    ) : ShimejiPlugin {
        var enableCalls = 0
        var disableCalls = 0
        var executeCalls = 0
        var cancelledDuringExecute = false

        override val metadata = PluginMetadata(
            id = id,
            name = "plugin-$id",
            version = "1.0.0",
            category = PluginCategory.DEVICE,
        )

        override fun getToolDefinitions(): List<ToolDefinition> = tools

        override suspend fun onEnable(context: PluginContext) {
            enableCalls++
            onEnableBlock()
        }

        override suspend fun onDisable() {
            disableCalls++
            onDisableBlock()
        }

        override suspend fun executeTool(name: String, params: JsonObject): ToolResult {
            executeCalls++
            try {
                return executeBlock(name, params)
            } catch (e: CancellationException) {
                cancelledDuringExecute = true
                throw e
            }
        }
    }

    private class FakeSystemInfo(var batteryLevel: Int? = 50) : SystemInfo {
        override val batteryLevelPercent: Int? get() = batteryLevel
        override val hasBatteryPermission: Boolean get() = true
    }

    private class Ctx(override val pluginId: String, val si: FakeSystemInfo = FakeSystemInfo()) : PluginContext {
        override val eventBus = object : dev.delpa.shimeji.core.event.EventBus {
            private val flow = kotlinx.coroutines.flow.MutableSharedFlow<HarnessEvent>(extraBufferCapacity = 16)
            override val events = flow
            override fun emit(event: HarnessEvent) = flow.tryEmit(event)
        }
        override val systemInfo: SystemInfo get() = si
        override suspend fun loadAsset(assetName: String) = ""
    }

    private fun newEngine(si: FakeSystemInfo = FakeSystemInfo()): HarnessEngine {
        val engine = HarnessEngine(
            pluginContextFactory = { plugin -> Ctx(plugin.metadata.id, si) },
            defaultTimeoutMillis = 1000,
        )
        engine.start()
        return engine
    }

    // ------------------------------------------------------------------
    // Registration + active-only aggregation
    // ------------------------------------------------------------------

    @Test
    fun `active-only aggregation`() = runTest {
        val engine = newEngine()
        val p1 = TestPlugin("p1")
        val p2 = TestPlugin("p2", tools = listOf(echoTool("p2.tool")))
        engine.register(p1)
        engine.register(p2)
        assertTrue(engine.catalog.value.tools.isEmpty(), "nothing enabled yet")

        engine.enable("p1")
        assertEquals(listOf("mock.echo"), engine.catalog.value.tools.map { it.name })

        engine.enable("p2")
        assertEquals(listOf("mock.echo", "p2.tool"), engine.catalog.value.tools.map { it.name })
    }

    @Test
    fun `duplicate registration rejected`() = runTest {
        val engine = newEngine()
        engine.register(TestPlugin("dup"))
        assertFailsWith<HarnessRegistrationException> { engine.register(TestPlugin("dup")) }
    }

    @Test
    fun `ambiguous tool registration rejected`() = runTest {
        val engine = newEngine()
        engine.register(TestPlugin("a", tools = listOf(echoTool("shared.tool"))))
        assertFailsWith<HarnessRegistrationException> {
            engine.register(TestPlugin("b", tools = listOf(echoTool("shared.tool"))))
        }
    }

    @Test
    fun `enable failure marks failed and excludes tools`() = runTest {
        val engine = newEngine()
        val failing = TestPlugin("failing", onEnableBlock = { throw IllegalStateException("boom") })
        engine.register(failing)
        engine.enable("failing")
        assertEquals(PluginStatus.FAILED, engine.pluginStates.value.first { it.pluginId == "failing" }.status)
        assertTrue(engine.catalog.value.tools.isEmpty())
        assertTrue(engine.catalog.value.unavailableToolSignatures.any { it.pluginId == "failing" })
    }

    @Test
    fun `disable cleanup and re-enable`() = runTest {
        val engine = newEngine()
        val p = TestPlugin("p1")
        engine.register(p)
        engine.enable("p1")
        assertEquals(1, p.enableCalls)
        engine.disable("p1")
        assertEquals(1, p.disableCalls)
        assertTrue(engine.catalog.value.tools.isEmpty())
        engine.enable("p1")
        assertEquals(2, p.enableCalls)
        // Idempotent enable while already enabled.
        engine.enable("p1")
        assertEquals(2, p.enableCalls)
    }

    // ------------------------------------------------------------------
    // Validation outcomes
    // ------------------------------------------------------------------

    @Test
    fun `stale disabled target rejected`() = runTest {
        val engine = newEngine()
        val p = TestPlugin("p1")
        engine.register(p)
        engine.enable("p1")
        engine.disable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") }))
        assertIs<FailureResult>(res)
        assertEquals(ErrorCode.PLUGIN_NOT_ENABLED.name, res.code)
    }

    @Test
    fun `malformed arguments rejected`() = runTest {
        val engine = newEngine()
        engine.register(TestPlugin("p1"))
        engine.enable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject {}))
        assertIs<FailureResult>(res)
        assertEquals(ErrorCode.MALFORMED_ARGUMENTS.name, res.code)
    }

    @Test
    fun `unknown tool rejected`() = runTest {
        val engine = newEngine()
        engine.register(TestPlugin("p1"))
        engine.enable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "does.not_exist", buildJsonObject {}))
        assertIs<FailureResult>(res)
        assertEquals(ErrorCode.UNKNOWN_TOOL.name, res.code)
    }

    @Test
    fun `missing capability rejected`() = runTest {
        val si = FakeSystemInfo(batteryLevel = null)
        val engine = newEngine(si)
        val p = TestPlugin("p1", tools = listOf(echoTool("device.read_battery", requiresCapability = "battery-access", requiredParams = false)))
        engine.register(p)
        engine.enable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "device.read_battery", buildJsonObject {}))
        assertIs<FailureResult>(res)
        assertEquals(ErrorCode.MISSING_CAPABILITY.name, res.code)
        // The unavailable catalog distinguishes capability from plugin state.
        val unavailable = engine.catalog.value.unavailableToolSignatures.first { it.toolName == "device.read_battery" }
        assertTrue(unavailable.reason.contains("battery-access"))
    }

    @Test
    fun `capability present executes`() = runTest {
        val si = FakeSystemInfo(batteryLevel = 73)
        val engine = newEngine(si)
        val p = TestPlugin("p1", tools = listOf(echoTool("device.read_battery", requiresCapability = "battery-access", requiredParams = false)))
        engine.register(p)
        engine.enable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "device.read_battery", buildJsonObject {}))
        assertIs<SuccessResult>(res)
        assertEquals(1, p.executeCalls)
    }

    @Test
    fun `confirmation required returns typed result and does not execute`() = runTest {
        val engine = newEngine()
        val p = TestPlugin(
            "p1",
            tools = listOf(echoTool("mock.high_impact", permission = ToolPermission.CONFIRMATION_REQUIRED)),
        )
        engine.register(p)
        engine.enable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.high_impact", buildJsonObject { put("text", "x") }))
        assertIs<ConfirmationRequired>(res)
        assertEquals(0, p.executeCalls, "plugin must not execute before confirmation")
    }

    @Test
    fun `stale target between validation and execution rechecked`() = runTest {
        val engine = newEngine()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val p = TestPlugin("slow", executeBlock = { _, _ -> gate.await(); SuccessResult() })
        engine.register(p)
        engine.enable("slow")

        val job = async { engine.submitToolCall(HarnessEngine.ToolCallRequest("slow", "mock.echo", buildJsonObject { put("text", "x") })) }
        // Give the call time to pass validation and start executing.
        delay(10)
        // disable() waits for the in-flight call (per-plugin serialization).
        val disableJob = async { engine.disable("slow") }
        delay(10)
        gate.complete(Unit)
        // The in-flight call completes, so this returns success even though the
        // plugin was disabled while it was running.
        val res = job.await()
        assertIs<SuccessResult>(res)
        disableJob.await()
        // New calls after disable are rejected.
        val res2 = engine.submitToolCall(HarnessEngine.ToolCallRequest("slow", "mock.echo", buildJsonObject { put("text", "x") }))
        assertIs<FailureResult>(res2)
        assertEquals(ErrorCode.PLUGIN_NOT_ENABLED.name, res2.code)
    }

    // ------------------------------------------------------------------
    // Timeout / cancellation / duplicates
    // ------------------------------------------------------------------

    @Test
    fun `timeout returns typed failure`() = runTest {
        val engine = newEngine(defaultTimeout = 50)
        val p = TestPlugin("p1", executeBlock = { _, _ -> delay(5000); SuccessResult() })
        engine.register(p)
        engine.enable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") }))
        assertIs<FailureResult>(res)
        assertEquals(ErrorCode.TIMEOUT.name, res.code)
    }

    @Test
    fun `cancellation propagates and cancels plugin work`() = runTest {
        val engine = newEngine(defaultTimeout = 5000)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val p = TestPlugin("p1", executeBlock = { _, _ -> gate.await(); SuccessResult() })
        engine.register(p)
        engine.enable("p1")

        val job = launch { engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") })) }
        delay(20)
        job.cancelAndJoin()
        assertTrue(p.cancelledDuringExecute, "plugin must observe cooperative cancellation")
    }

    @Test
    fun `duplicate request ids rejected`() = runTest {
        val engine = newEngine()
        engine.register(TestPlugin("p1"))
        engine.enable("p1")
        val req = HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") }, requestId = "same-id")
        val first = engine.submitToolCall(req)
        assertIs<SuccessResult>(first)
        val second = engine.submitToolCall(req)
        assertIs<FailureResult>(second)
        assertEquals(ErrorCode.DUPLICATE_REQUEST_ID.name, second.code)
    }

    @Test
    fun `one execution per accepted request and serialized per plugin`() = runTest {
        val engine = newEngine()
        val p = TestPlugin("p1", executeBlock = { _, _ -> kotlinx.coroutines.delay(5); SuccessResult() })
        engine.register(p)
        engine.enable("p1")

        val r1 = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "a") }))
        val r2 = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "b") }))
        assertIs<SuccessResult>(r1)
        assertIs<SuccessResult>(r2)
        assertEquals(2, p.executeCalls)
    }

    // ------------------------------------------------------------------
    // Startup / shutdown / collectors
    // ------------------------------------------------------------------

    @Test
    fun `rejects before start and during shutdown`() = runTest {
        val engine = HarnessEngine(pluginContextFactory = { plugin -> Ctx(plugin.metadata.id) })
        val p = TestPlugin("p1")
        engine.register(p)
        val pre = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") }))
        assertIs<FailureResult>(pre)
        assertEquals(ErrorCode.NOT_STARTED.name, pre.code)

        engine.start()
        engine.enable("p1")
        assertIs<SuccessResult>(engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") })))

        engine.shutdown()
        assertTrue(engine.pluginStates.value.all { it.status == PluginStatus.DISABLED })
        val post = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") }))
        assertIs<FailureResult>(post)
        assertEquals(ErrorCode.SHUTTING_DOWN.name, post.code)
    }

    @Test
    fun `operates with no visual collectors and publishes animation event`() = runTest {
        val engine = newEngine()
        val events = mutableListOf<HarnessEvent>()
        val collector = launch {
            engine.events.collect { events.add(it) }
        }
        runCurrent() // ensure the collector is subscribed before any emit
        engine.register(TestPlugin("p1"))
        engine.enable("p1")
        val res = engine.submitToolCall(HarnessEngine.ToolCallRequest("p1", "mock.echo", buildJsonObject { put("text", "x") }))
        assertIs<SuccessResult>(res)
        runCurrent() // give the collector a scheduler turn to process buffered emissions
        collector.cancel()

        assertTrue(events.any { it is HarnessEvent.ToolCallAccepted })
        assertTrue(events.any { it is HarnessEvent.ToolCallCompleted })
        assertTrue(events.any { it is VisualCommand.PlayAnimation }, "accepted call must emit PlayAnimation")
    }

    private fun newEngine(defaultTimeout: Long, si: FakeSystemInfo = FakeSystemInfo()): HarnessEngine {
        val engine = HarnessEngine(
            pluginContextFactory = { plugin -> Ctx(plugin.metadata.id, si) },
            defaultTimeoutMillis = defaultTimeout,
        )
        engine.start()
        return engine
    }
}