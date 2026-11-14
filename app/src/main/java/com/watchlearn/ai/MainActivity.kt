package com.watchlearn.ai

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private val DEFAULT_OPENAI_MODELS = listOf(
    "gpt-5",
    "gpt-5-mini",
    "gpt-5-nano",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4o",
    "gpt-4o-mini",
    "o3",
    "o3-mini",
    "o4-mini"
)

private data class Automation(
    val name: String,
    val instruction: String
)

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
        var showAutomationDialog by remember { mutableStateOf(false) }
        var apiKey by rememberSaveable { mutableStateOf("") }
        var connectedProvider by rememberSaveable { mutableStateOf("") }
        var selectedModel by rememberSaveable { mutableStateOf(DEFAULT_OPENAI_MODELS.first()) }
        var availableModels by remember { mutableStateOf(DEFAULT_OPENAI_MODELS) }
        var isRefreshingModels by remember { mutableStateOf(false) }
        var taskStatus by rememberSaveable { mutableStateOf("Ready for a task.") }
        var taskOutput by rememberSaveable { mutableStateOf("") }
        var deploymentMode by rememberSaveable { mutableStateOf("Personal sideload mode") }
        val automations = remember { mutableStateListOf<Automation>() }
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
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
                val intent = Intent(this@MainActivity, ScreenRecordingService::class.java).apply {
                    putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
                taskStatus = "Screen recording started for the approved task."
            } else {
                taskStatus = "Screen recording was cancelled."
            }
        }

        fun runTask(description: String) {
            val trimmed = description.trim()
            if (trimmed.isEmpty()) {
                taskStatus = "Describe a task before pressing Run task now."
                taskOutput = ""
                return
            }
            if (apiKey.isBlank()) {
                taskStatus = "Task saved, but an OpenAI API key is required to run it."
                taskOutput = "Open Settings → Connect an API key, then run this task again."
                return
            }

            taskStatus = "Running task with $selectedModel..."
            taskOutput = ""
            scope.launch {
                try {
                    taskOutput = requestTaskPlan(apiKey, selectedModel, trimmed)
                    taskStatus = "Task completed with $selectedModel."
                } catch (error: Exception) {
                    taskStatus = "Task could not run."
                    taskOutput = error.message ?: "OpenAI request failed."
                }
            }
        }

        fun refreshModels() {
            if (apiKey.isBlank()) {
                taskStatus = "Connect an OpenAI API key before refreshing models."
                return
            }
            isRefreshingModels = true
            taskStatus = "Loading models available to this API key..."
            scope.launch {
                try {
                    val models = requestOpenAiModels(apiKey)
                    if (models.isNotEmpty()) {
                        availableModels = models
                        if (selectedModel !in models) selectedModel = models.first()
                        taskStatus = "Loaded " + models.size + " models available to this API key."
                    } else {
                        taskStatus = "No chat-capable models were returned."
                    }
                } catch (error: Exception) {
                    taskStatus = error.message ?: "Could not load models."
                } finally {
                    isRefreshingModels = false
                }
            }
        }

        Scaffold { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(padding)
            ) {
                Text(
                    "WatchLearn AI",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
                Text(
                    "Watch. Learn. Challenge Yourself.",
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                TabRow(selectedTabIndex = tab) {
                    listOf("Chat", "Automations", "Models", "Settings").forEachIndexed { index, label ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(label) }
                        )
                    }
                }
                when (tab) {
                    0 -> ChatScreen(
                        selectedModel = selectedModel,
                        taskStatus = taskStatus,
                        taskOutput = taskOutput,
                        onPlanTask = { runTask(it) },
                        onRunTask = { runTask(it) },
                        onRecord = { showRecordingConsent = true },
                        onStopRecording = {
                            startService(
                                Intent(
                                    this@MainActivity,
                                    ScreenRecordingService::class.java
                                ).setAction(ScreenRecordingService.ACTION_STOP)
                            )
                            taskStatus = "Stop recording requested."
                        },
                        onAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            taskStatus = "Android accessibility settings opened."
                        }
                    )
                    1 -> AutomationScreen(
                        automations = automations,
                        onCreate = { showAutomationDialog = true },
                        onRun = { runTask(it) },
                        onDelete = { automations.remove(it) }
                    )
                    2 -> ModelsScreen(
                        models = availableModels,
                        selectedModel = selectedModel,
                        isRefreshing = isRefreshingModels,
                        onSelect = {
                            selectedModel = it
                            taskStatus = "Selected model: $it"
                        },
                        onRefresh = { refreshModels() }
                    )
                    else -> SettingsScreen(
                        connectedProvider = connectedProvider,
                        deploymentMode = deploymentMode,
                        onAddKey = { showKeyDialog = true },
                        onModeSelected = {
                            deploymentMode = it
                            taskStatus = "Selected $it."
                        },
                        onClearKey = {
                            apiKey = ""
                            connectedProvider = ""
                            taskStatus = "API key disconnected from this session."
                        }
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    taskStatus,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        if (showRecordingConsent) {
            AlertDialog(
                onDismissRequest = { showRecordingConsent = false },
                title = { Text("Allow screen recording?") },
                text = {
                    Text(
                        "WatchLearn AI will record the selected screen while the approved task runs. Android will show its own recording permission prompt and a persistent recording indicator. You can stop it at any time."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRecordingConsent = false
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(manager.createScreenCaptureIntent())
                    }) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { showRecordingConsent = false }) { Text("Cancel") }
                }
            )
        }
        if (showKeyDialog) {
            ApiKeyDialog(
                onDismiss = { showKeyDialog = false },
                onSave = { provider, key ->
                    connectedProvider = provider
                    apiKey = key
                    showKeyDialog = false
                    taskStatus = provider + " API key connected for this session."
                }
            )
        }
        if (showAutomationDialog) {
            CreateAutomationDialog(
                onDismiss = { showAutomationDialog = false },
                onCreate = { name, instruction ->
                    automations.add(Automation(name, instruction))
                    showAutomationDialog = false
                    taskStatus = "Automation created: $name"
                }
            )
        }
    }
}

@Composable
private fun ChatScreen(
    selectedModel: String,
    taskStatus: String,
    taskOutput: String,
    onPlanTask: (String) -> Unit,
    onRunTask: (String) -> Unit,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onAccessibility: () -> Unit
) {
    var command by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Describe a task, choose a model, and run it immediately.")
        Text("Selected model: $selectedModel", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What should I do?") },
            minLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onPlanTask(command) }) { Text("Plan task") }
            Button(onClick = { onRunTask(command) }) { Text("Run task now") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRecord) { Text("Record approved task") }
            OutlinedButton(onClick = onStopRecording) { Text("Stop recording") }
        }
        OutlinedButton(onClick = onAccessibility) {
            Text("Enable Android accessibility")
        }
        if (taskOutput.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Text(taskOutput, modifier = Modifier.padding(16.dp))
            }
        }
        Text(
            "Run task now calls the selected OpenAI API model. Device-control actions still require Android accessibility to be enabled and an approved action executor.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AutomationScreen(
    automations: List<Automation>,
    onCreate: () -> Unit,
    onRun: (String) -> Unit,
    onDelete: (Automation) -> Unit
) {
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Automations", style = MaterialTheme.typography.titleLarge)
        Text(
            "Create reusable tasks. Run now uses the current model and API connection. Scheduled background execution will require a configured backend and Android background-work policy."
        )
        Button(onClick = onCreate) { Text("Create automation") }
        if (automations.isEmpty()) {
            Card { Text("No automations yet", modifier = Modifier.padding(16.dp)) }
        } else {
            automations.forEach { automation ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(automation.name, style = MaterialTheme.typography.titleMedium)
                        Text(automation.instruction)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRun(automation.instruction) }) {
                                Text("Run now")
                            }
                            TextButton(onClick = { onDelete(automation) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelsScreen(
    models: List<String>,
    selectedModel: String,
    isRefreshing: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("OpenAI API models", style = MaterialTheme.typography.titleLarge)
        Text(
            "The refresh button loads every model returned by the connected API key. The list below is a starter catalog for offline selection."
        )
        Button(onClick = onRefresh, enabled = !isRefreshing) {
            Text(if (isRefreshing) "Refreshing…" else "Refresh models from OpenAI")
        }
        models.forEach { model ->
            FilterChip(
                selected = selectedModel == model,
                onClick = { onSelect(model) },
                label = { Text(model) }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    connectedProvider: String,
    deploymentMode: String,
    onAddKey: () -> Unit,
    onModeSelected: (String) -> Unit,
    onClearKey: () -> Unit
) {
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(
            if (connectedProvider.isBlank()) {
                "No API key connected"
            } else {
                "Connected provider: $connectedProvider (key held in memory only)"
            }
        )
        Text("Current mode: $deploymentMode")
        Text(
            "For personal sideload testing, the key is used only for the current app session and is not written to Android preferences. A production customer app should send keys to an authenticated encrypted backend."
        )
        Button(onClick = onAddKey) { Text("Connect an API key") }
        if (connectedProvider.isNotBlank()) {
            OutlinedButton(onClick = onClearKey) { Text("Disconnect API key") }
        }
        OutlinedButton(onClick = { onModeSelected("Personal sideload mode") }) {
            Text("Use personal sideload mode")
        }
        OutlinedButton(onClick = { onModeSelected("Play Store mode") }) {
            Text("Use Play Store mode")
        }
    }
}

@Composable
private fun ApiKeyDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var provider by rememberSaveable { mutableStateOf("OpenAI") }
    var key by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = provider,
                    onValueChange = { provider = it },
                    label = { Text("Provider") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text("The key stays in memory for this personal sideload session and is sent only over HTTPS.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(provider.trim(), key.trim()) },
                enabled = provider.isNotBlank() && key.isNotBlank()
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CreateAutomationDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create automation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Automation name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("Task instruction") },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), instruction.trim()) },
                enabled = name.isNotBlank() && instruction.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private suspend fun requestOpenAiModels(apiKey: String): List<String> =
    withContext(Dispatchers.IO) {
        val connection = (URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer " + apiKey)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                throw IOException("OpenAI models request failed ($code): " + body.take(240))
            }
            val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            val models = buildList {
                for (index in 0 until data.length()) {
                    val id = data.optJSONObject(index)?.optString("id").orEmpty()
                    if (id.startsWith("gpt-") || id.startsWith("o")) add(id)
                }
            }
            models.distinct().sorted()
        } finally {
            connection.disconnect()
        }
    }

private suspend fun requestTaskPlan(
    apiKey: String,
    model: String,
    description: String
): String = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection)
    try {
        val payload = JSONObject()
            .put("model", model)
            .put(
                "input",
                "You are WatchLearn AI. Create a concise, safe action plan for this Android task. " +
                    "Do not claim you performed actions. Identify any step that needs user permission. Task: " +
                    description
            )
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", "Bearer " + apiKey)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            throw IOException("OpenAI task request failed ($code): " + body.take(320))
        }
        val json = JSONObject(body)
        val directText = json.optString("output_text")
        if (directText.isNotBlank()) return@withContext directText
        val output = json.optJSONArray("output") ?: return@withContext body
        val parts = buildList {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val text = content.optJSONObject(contentIndex)?.optString("text").orEmpty()
                    if (text.isNotBlank()) add(text)
                }
            }
        }
        parts.joinToString("\n").ifBlank { body }
    } finally {
        connection.disconnect()
    }
}

