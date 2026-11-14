package com.watchlearn.ai

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { WatchLearnTheme { WatchLearnApp() } }
    }

    @Composable
    private fun WatchLearnApp() {
        var tab by remember { mutableIntStateOf(0) }
        var showRecordingConsent by remember { mutableStateOf(false) }
        var showKeyDialog by remember { mutableStateOf(false) }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val projectionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenRecordingService::class.java).apply {
                    putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }

        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text("WatchLearn AI", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp))
                Text("Watch. Learn. Challenge Yourself.", modifier = Modifier.padding(horizontal = 20.dp))
                TabRow(selectedTabIndex = tab) {
                    listOf("Chat", "Automations", "Models", "Settings").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                when (tab) {
                    0 -> ChatScreen(
                        onRecord = { showRecordingConsent = true },
                        onStopRecording = {
                            startService(Intent(this, ScreenRecordingService::class.java).setAction(ScreenRecordingService.ACTION_STOP))
                        },
                        onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                    1 -> AutomationScreen()
                    2 -> ModelsScreen()
                    else -> SettingsScreen(onAddKey = { showKeyDialog = true })
                }
            }
        }

        if (showRecordingConsent) {
            AlertDialog(
                onDismissRequest = { showRecordingConsent = false },
                title = { Text("Allow screen recording?") },
                text = { Text("WatchLearn AI will record the selected screen while the approved task runs. Android will show its own recording permission prompt and a persistent recording indicator. You can stop it at any time.") },
                confirmButton = {
                    TextButton(onClick = {
                        showRecordingConsent = false
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(manager.createScreenCaptureIntent())
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = { showRecordingConsent = false }) { Text("Cancel") } }
            )
        }
        if (showKeyDialog) {
            ApiKeyDialog(onDismiss = { showKeyDialog = false })
        }
    }
}

@Composable
private fun ChatScreen(onRecord: () -> Unit, onStopRecording: () -> Unit, onAccessibility: () -> Unit) {
    var command by remember { mutableStateOf("") }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Describe a task. WatchLearn AI will show the actions and request permission before sensitive operations.")
        OutlinedTextField(command, { command = it }, modifier = Modifier.fillMaxWidth(), label = { Text("What should I do?") })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { }) { Text("Plan task") }
            OutlinedButton(onClick = onRecord) { Text("Record approved task") }
        }
        OutlinedButton(onClick = onStopRecording) { Text("Stop recording") }
        OutlinedButton(onClick = onAccessibility) { Text("Enable Android accessibility") }
    }
}

@Composable
private fun AutomationScreen() {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Automations", style = MaterialTheme.typography.titleLarge)
        Text("Scheduled workflows run through Android’s background-work rules. Screen recording still requires Android’s capture consent for each session.")
        Button(onClick = { }) { Text("Create automation") }
        Card { Text("No automations yet", modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun ModelsScreen() {
    val providers = listOf("OpenAI", "Anthropic", "Google Gemini", "OpenAI-compatible endpoint")
    var selected by remember { mutableStateOf(providers.first()) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Model provider", style = MaterialTheme.typography.titleLarge)
        providers.forEach { provider ->
            FilterChip(selected == provider, { selected = provider }, label = { Text(provider) })
        }
        Text("The production backend should maintain an allowlist of model IDs and enforce per-customer spending limits.")
    }
}

@Composable
private fun SettingsScreen(onAddKey: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("Backend: not configured")
        Text("API keys must be sent only to your authenticated backend over HTTPS. They should not be embedded in this APK.")
        Button(onClick = onAddKey) { Text("Connect an API key") }
        OutlinedButton(onClick = { }) { Text("Personal sideload mode") }
        OutlinedButton(onClick = { }) { Text("Play Store mode") }
    }
}

@Composable
private fun ApiKeyDialog(onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf("OpenAI") }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(provider, { provider = it }, label = { Text("Provider") })
                OutlinedTextField(key, { key = it }, label = { Text("API key") })
                Text("This MVP sends the key to the configured backend only after you confirm. It does not save the raw key in Android preferences.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Send to backend") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
