package dev.delpa.shimeji.app

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.delpa.shimeji.core.event.EventBus
import dev.delpa.shimeji.core.event.HarnessEvent
import dev.delpa.shimeji.core.harness.HarnessEngine
import dev.delpa.shimeji.core.model.MockAgentPlugin
import dev.delpa.shimeji.core.plugin.MockPlugin
import dev.delpa.shimeji.core.plugin.PluginContext
import dev.delpa.shimeji.core.plugin.ShimejiPlugin
import dev.delpa.shimeji.core.plugin.SystemInfo
import dev.delpa.shimeji.core.plugin.device.DeviceControlPlugin
import dev.delpa.shimeji.overlay.ShimejiAppHost
import kotlinx.coroutines.runBlocking

/**
 * Composition root. Owns the SINGLE shared [HarnessEngine] + MockAgentPlugin.
 * Activity and Service receive these instances from here; neither creates its
 * own registry, so plugin state and catalog are genuinely shared.
 */
class ShimejiHarnessApplication : Application(), ShimejiAppHost {

    lateinit var engine: HarnessEngine
        private set
    lateinit var mockAgent: MockAgentPlugin
        private set

    private val systemInfo = AppSystemInfo(this)

    override fun onCreate() {
        super.onCreate()

        engine = HarnessEngine(
            pluginContextFactory = { plugin ->
                AppPluginContext(
                    pluginId = plugin.metadata.id,
                    eventBus = object : EventBus {
                        override val events = engine.events
                        override fun emit(event: HarnessEvent): Boolean = false
                    },
                    systemInfo = systemInfo,
                    context = this,
                )
            },
        )
        mockAgent = MockAgentPlugin(engine)

        // Register built-ins deterministically; enable the demo visual plugin.
        runBlocking {
            engine.start()
            engine.register(DeviceControlPlugin(batteryProvider = { systemInfo.batteryLevelPercent }))
            engine.register(MockPlugin())
            engine.register(DemoVisualPlugin())
            engine.enable("visual")
        }
    }

    override fun harnessEngine(): HarnessEngine = engine

    override fun mainActivityIntent(): Intent = Intent(this, MainActivity::class.java)
}

class AppPluginContext(
    override val pluginId: String,
    override val eventBus: EventBus,
    override val systemInfo: SystemInfo,
    private val context: Context,
) : PluginContext {
    override suspend fun loadAsset(assetName: String): String =
        context.assets.open(assetName).bufferedReader().use { it.readText() }
}

class AppSystemInfo(private val context: Context) : SystemInfo {
    private val batteryManager: BatteryManager?
        get() = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    override val batteryLevelPercent: Int?
        get() = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    override val hasBatteryPermission: Boolean
        get() = Build.VERSION.SDK_INT < 23 ||
            context.checkSelfPermission(android.Manifest.permission.BATTERY_STATS) ==
            PackageManager.PERMISSION_GRANTED
}