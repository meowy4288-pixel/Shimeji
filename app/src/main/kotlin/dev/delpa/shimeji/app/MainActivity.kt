package dev.delpa.shimeji.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.delpa.shimeji.core.plugin.PluginStatus

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val charManager = dev.delpa.shimeji.overlay.CharacterManager(this)

    // Observable URI + auto-derived name for the import dialog.
    private val _pendingImportUri = mutableStateOf<Uri?>(null)
    private val _pendingImportName = mutableStateOf("")

    private val importLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                // Take persistable read permission so we can access the zip later.
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val derivedName = charManager.deriveNameFromUri(uri)
                _pendingImportName.value = derivedName
                _pendingImportUri.value = uri
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val pendingUri by _pendingImportUri

            ShimejiHarnessTheme {
                MainScreen(
                    viewModel = vm,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onImportCharacter = {
                        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    },
                )

                // Show name input dialog when a zip has been picked.
                val uri = pendingUri
                val derivedName by _pendingImportName
                if (uri != null) {
                    ImportNameDialog(
                        initialName = derivedName,
                        onConfirm = { name ->
                            vm.importCharacterZip(name, uri)
                            _pendingImportUri.value = null
                        },
                        onDismiss = {
                            _pendingImportUri.value = null
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
    onImportCharacter: () -> Unit,
) {
    val overlayGranted by viewModel.overlayGranted.collectAsState()
    val notificationsGranted by viewModel.notificationsGranted.collectAsState()
    val batteryPermission by viewModel.batteryPermission.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val pluginStates by viewModel.engine.pluginStates.collectAsState()
    val catalog by viewModel.engine.catalog.collectAsState()
    val tapPoke by viewModel.tapPoke.collectAsState()
    val tapFacing by viewModel.tapFacing.collectAsState()
    val walk by viewModel.walk.collectAsState()
    val idleVariety by viewModel.idleVariety.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val selectedCharacter by viewModel.selectedCharacter.collectAsState()
    val mascotScale by viewModel.mascotScale.collectAsState()

    // Re-check permissions whenever the activity returns to the foreground
    // (covers "grant overlay then come back" as well as revocation while away).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Shimeji Harness") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = "Permissions",
                content = {
                    StatusRow(
                        label = "Overlay (draw over apps)",
                        value = if (overlayGranted) "Granted" else "Not granted",
                        action = {
                            OutlinedButton(onClick = { viewModel.openOverlaySettings() }) {
                                Text("Manage")
                            }
                        },
                    )
                    StatusRow(
                        label = "Notifications",
                        value = if (notificationsGranted) "Granted" else "Not granted",
                        action = {
                            OutlinedButton(
                                onClick = onRequestNotificationPermission,
                                enabled = !notificationsGranted,
                            ) { Text("Request") }
                        },
                    )
                    StatusRow(
                        label = "Battery stats (informational)",
                        value = if (batteryPermission) "Granted" else "Not granted",
                    )
                },
            )

            SectionCard(
                title = "Mascot",
                content = {
                    StatusRow(
                        label = "Overlay service",
                        value = if (overlayGranted) "Ready" else "Requires overlay permission",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.startMascot() },
                            enabled = overlayGranted,
                            modifier = Modifier.weight(1f),
                        ) { Text("Start mascot") }
                        OutlinedButton(
                            onClick = { viewModel.stopMascot() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Stop mascot") }
                    }
                },
            )

            SectionCard(
                title = "Plugins",
                content = {
                    pluginStates.forEach { plugin ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${plugin.pluginName} (${plugin.pluginId}) · ${plugin.status.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                plugin.errorMessage?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Switch(
                                checked = plugin.status == PluginStatus.ENABLED,
                                onCheckedChange = { checked ->
                                    viewModel.togglePlugin(plugin.pluginId, checked)
                                },
                            )
                        }
                    }
                },
            )

            SectionCard(
                title = "Mascot behavior",
                content = {
                    ToggleRow(
                        label = "Tap to interact",
                        description = "Tap the mascot to make it jump, bounce, or poke",
                        checked = tapPoke,
                        onCheckedChange = viewModel::setTapPoke,
                    )
                    ToggleRow(
                        label = "Face tap direction",
                        description = "Tap left/right side to make the mascot face that way",
                        checked = tapFacing,
                        onCheckedChange = viewModel::setTapFacing,
                    )
                    ToggleRow(
                        label = "Walk",
                        description = "Mascot walks around autonomously when idle",
                        checked = walk,
                        onCheckedChange = viewModel::setWalk,
                    )
                    ToggleRow(
                        label = "Idle variety",
                        description = "Mascot sits, dangles legs, lies down, looks up",
                        checked = idleVariety,
                        onCheckedChange = viewModel::setIdleVariety,
                    )
                },
            )

            SectionCard(
                title = "Characters",
                content = {
                    // Character selector
                    Text("Active character", style = MaterialTheme.typography.labelSmall)
                    val allCharacters = listOf("") + characters
                    allCharacters.forEach { name ->
                        val displayName = if (name.isEmpty()) "Classic (bundled)" else name
                        val isSelected = name == selectedCharacter
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.selectCharacter(name) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (name.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { viewModel.deleteCharacter(name) },
                                ) { Text("Delete") }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onImportCharacter) {
                        Text("Import character…")
                    }

                    // Scale slider
                    Spacer(Modifier.height(8.dp))
                    Text("Mascot scale: ${"%.1f".format(mascotScale)}×", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = mascotScale,
                        onValueChange = viewModel::setMascotScale,
                        valueRange = 0.5f..1.5f,
                        steps = 9,
                    )
                },
            )

            SectionCard(
                title = "Tool catalog (revision ${catalog.revision})",
                content = {
                    if (catalog.tools.isEmpty()) {
                        Text("No callable tools. Enable a plugin below, or run the mock agent (its echo tool is callable once enabled).", style = MaterialTheme.typography.bodySmall)
                    }
                    catalog.tools.forEach { tool ->
                        Text(
                            tool.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            tool.description,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (catalog.unavailableToolSignatures.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Unavailable:", style = MaterialTheme.typography.labelSmall)
                        catalog.unavailableToolSignatures.forEach { u ->
                            Text(
                                "${u.pluginId} / ${u.toolName}: ${u.reason}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
            )

            SectionCard(
                title = "Mock agent (manual demo trigger)",
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.runMockAgent() }, modifier = Modifier.weight(1f)) {
                            Text("Run mock agent")
                        }
                        OutlinedButton(onClick = { viewModel.runMockAgentDirect() }, modifier = Modifier.weight(1f)) {
                            Text("Call battery directly")
                        }
                    }
                    lastResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
            )

            Text(
                "Demo visuals are procedural; overlay runs on API 26+.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
        action?.let {
            Spacer(Modifier.height(0.dp))
            it()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ImportNameDialog(
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import character") },
        text = {
            Column {
                Text("Enter a name for this character:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
                    placeholder = { Text("e.g. cat_girl") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}