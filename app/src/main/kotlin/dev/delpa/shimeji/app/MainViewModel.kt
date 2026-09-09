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
import dev.delpa.shimeji.overlay.CharacterManager
import dev.delpa.shimeji.overlay.MascotPrefs
import dev.delpa.shimeji.overlay.ShimejiOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val harness: ShimejiHarnessApplication = app as ShimejiHarnessApplication
    val engine get() = harness.engine
    private val mockAgent get() = harness.mockAgent
    val mascotPrefs = MascotPrefs(app)

    private val _tapPoke = MutableStateFlow(mascotPrefs.tapPoke)
    val tapPoke: StateFlow<Boolean> = _tapPoke.asStateFlow()

    private val _tapFacing = MutableStateFlow(mascotPrefs.tapFacing)
    val tapFacing: StateFlow<Boolean> = _tapFacing.asStateFlow()

    private val _walk = MutableStateFlow(mascotPrefs.walk)
    val walk: StateFlow<Boolean> = _walk.asStateFlow()

    private val _idleVariety = MutableStateFlow(mascotPrefs.idleVariety)
    val idleVariety: StateFlow<Boolean> = _idleVariety.asStateFlow()

    private val _appAwareness = MutableStateFlow(mascotPrefs.appAwareness)
    val appAwareness: StateFlow<Boolean> = _appAwareness.asStateFlow()

    private val charManager = CharacterManager(app)

    private val _characters = MutableStateFlow(charManager.list())
    val characters: StateFlow<List<String>> = _characters.asStateFlow()

    private val _selectedCharacter = MutableStateFlow(mascotPrefs.selectedCharacter)
    val selectedCharacter: StateFlow<String> = _selectedCharacter.asStateFlow()

    private val _mascotScale = MutableStateFlow(mascotPrefs.mascotScale)
    val mascotScale: StateFlow<Float> = _mascotScale.asStateFlow()

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

    fun setTapPoke(enabled: Boolean) {
        mascotPrefs.tapPoke = enabled
        _tapPoke.value = enabled
    }

    fun setTapFacing(enabled: Boolean) {
        mascotPrefs.tapFacing = enabled
        _tapFacing.value = enabled
    }

    fun setWalk(enabled: Boolean) {
        mascotPrefs.walk = enabled
        _walk.value = enabled
    }

    fun setAppAwareness(enabled: Boolean) {
        mascotPrefs.appAwareness = enabled
        _appAwareness.value = enabled
    }

    fun setIdleVariety(enabled: Boolean) {
        mascotPrefs.idleVariety = enabled
        _idleVariety.value = enabled
    }

    fun importCharacterZip(name: String, uri: Uri) {
        viewModelScope.launch {
            _lastResult.value = "Importing '$name'..."
            val result = withContext(Dispatchers.IO) {
                charManager.importFromZip(name, uri)
            }
            if (result != null) {
                _characters.value = charManager.list()
                _selectedCharacter.value = result
                mascotPrefs.selectedCharacter = result
                _lastResult.value = "Imported '$name'. It is selected and applied."
                applyCharacterToOverlay()
            } else {
                _lastResult.value = "Import failed: zip missing poses.json or unreadable. Some zips need to be a plain folder with poses.json + PNG frames."
            }
        }
    }

    /**
     * Pick a character. If the mascot is running, restart the overlay so the
     * new renderer actually takes effect (the service only loads the renderer
     * once at attach time).
     */
    fun selectCharacter(name: String) {
        mascotPrefs.selectedCharacter = name
        _selectedCharacter.value = name
        _lastResult.value = "Selected: ${if (name.isEmpty()) "Classic (bundled)" else name}"
        applyCharacterToOverlay()
    }

    /** Reload the overlay renderer (restart service) so the selected char applies. */
    private fun applyCharacterToOverlay() {
        if (!Settings.canDrawOverlays(harness) || !ShimejiOverlayService.overlayActive) return
        viewModelScope.launch {
            harness.startService(
                ShimejiOverlayService.intent(harness).setAction(ShimejiOverlayService.ACTION_STOP)
            )
            delay(350)
            harness.startService(
                ShimejiOverlayService.intent(harness).setAction(ShimejiOverlayService.ACTION_START)
            )
        }
    }

    /** Load a character preview thumbnail (blocking decode; call from IO). */
    fun previewBitmap(name: String): android.graphics.Bitmap? = charManager.getPreviewBitmap(name)

    fun deleteCharacter(name: String) {
        charManager.delete(name)
        _characters.value = charManager.list()
        if (mascotPrefs.selectedCharacter == name) {
            mascotPrefs.selectedCharacter = ""
            _selectedCharacter.value = ""
        }
    }

    fun setMascotScale(scale: Float) {
        mascotPrefs.mascotScale = scale
        _mascotScale.value = scale
    }

    // ------------------------------------------------------------
    // Simulation — trigger mascot reactions directly from UI
    // ------------------------------------------------------------

    /** Trigger a specific FSM state on the running mascot via broadcast. */
    fun triggerMascotState(stateId: String) {
        harness.startService(
            ShimejiOverlayService.intent(harness)
                .setAction(ShimejiOverlayService.ACTION_TRIGGER)
                .putExtra("state", stateId)
        )
        _lastResult.value = "Triggered: $stateId"
    }

    /** Simulate an app context change to test app awareness reactions. */
    fun simulateAppContext(category: String) {
        harness.startService(
            ShimejiOverlayService.intent(harness)
                .setAction(ShimejiOverlayService.ACTION_SIMULATE)
                .putExtra("category", category)
        )
        _lastResult.value = "Simulated: $category context"
    }
}