package dev.delpa.shimeji.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.delpa.shimeji.core.harness.ConfirmationRequired
import dev.delpa.shimeji.core.harness.FailureResult
import dev.delpa.shimeji.core.harness.SuccessResult
import dev.delpa.shimeji.core.harness.ToolNames
import dev.delpa.shimeji.overlay.ShimejiOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val harness: ShimejiHarnessApplication = app as ShimejiHarnessApplication
    val engine get() = harness.engine
    private val mockAgent get() = harness.mockAgent

    private val _overlayGranted = MutableStateFlow(false)
    val overlayGranted: StateFlow<Boolean> = _overlayGranted.asStateFlow()

    private val _notificationsGranted = MutableStateFlow(true)
    val notificationsGranted: StateFlow<Boolean> = _notificationsGranted.asStateFlow()

    private val _batteryPermission = MutableStateFlow(false)
    val batteryPermission: StateFlow<Boolean> = _batteryPermission.asStateFlow()

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun refreshPermissions() {
        _overlayGranted.value = Settings.canDrawOverlays(harness)
        _notificationsGranted.value = Build.VERSION.SDK_INT < 33 ||
            harness.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        _batteryPermission.value = harness.checkSelfPermission(Manifest.permission.BATTERY_STATS) == PackageManager.PERMISSION_GRANTED
    }

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${harness.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        harness.startActivity(intent)
    }

    fun startMascot() {
        val intent = ShimejiOverlayService.intent(harness).setAction(ShimejiOverlayService.ACTION_START)
        androidx.core.content.ContextCompat.startForegroundService(harness, intent)
    }

    fun stopMascot() {
        // Deliver the STOP action so the foreground notification is removed;
        // a plain stopService() would leave the notification behind.
        harness.startService(
            ShimejiOverlayService.intent(harness).setAction(ShimejiOverlayService.ACTION_STOP),
        )
    }

    fun togglePlugin(pluginId: String, enable: Boolean) {
        viewModelScope.launch {
            if (enable) engine.enable(pluginId) else engine.disable(pluginId)
        }
    }

    fun runMockAgent() {
        viewModelScope.launch {
            _lastResult.value = "Proposing mock call..."
            val proposals = mockAgent.proposeCalls("demo: inspect device")
            if (proposals.isEmpty()) {
                _lastResult.value = "No callable tools in catalog (enable the device plugin first)."
                return@launch
            }
            val proposal = proposals.first()
            _lastResult.value = "Request ${proposal.requestId} -> ${proposal.toolName} ${proposal.params}"
            val result = mockAgent.submitAndCollect(proposal)
            _lastResult.value = when (result) {
                is SuccessResult -> {
                    val pretty = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result.data)
                    "SUCCESS: ${result.summary}\n$pretty"
                }
                is FailureResult -> "FAILURE [${result.code}]: ${result.message}"
                is ConfirmationRequired -> "CONFIRMATION REQUIRED: ${result.summary}"
                else -> "OK (no data)"
            }
        }
    }

    /** Demo e2e for REJECTION path: call directly even if plugin disabled. */
    fun runMockAgentDirect() {
        viewModelScope.launch {
            val result = engine.submitFromAgent(
                pluginId = "device",
                toolName = ToolNames.READ_BATTERY,
                params = buildJsonObject {},
            )
            _lastResult.value = when (result) {
                is SuccessResult -> "SUCCESS: ${result.summary}"
                is FailureResult -> "FAILURE [${result.code}]: ${result.message}"
                is ConfirmationRequired -> "CONFIRMATION REQUIRED: ${result.summary}"
                else -> "OK"
            }
        }
    }
}