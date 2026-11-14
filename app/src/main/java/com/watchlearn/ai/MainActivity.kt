package com.watchlearn.ai

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.ScrollableTabRow
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
import org.json.JSONArray
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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

private data class ImportedChat(
    val title: String,
    val transcript: String
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
        var apiBaseUrl by rememberSaveable { mutableStateOf("https://api.openai.com/v1") }
        var selectedModel by rememberSaveable { mutableStateOf(DEFAULT_OPENAI_MODELS.first()) }
        var availableModels by remember { mutableStateOf(DEFAULT_OPENAI_MODELS) }
        var isRefreshingModels by remember { mutableStateOf(false) }
        var taskStatus by rememberSaveable { mutableStateOf("Ready for a task.") }
        var taskOutput by rememberSaveable { mutableStateOf("") }
        var deploymentMode by rememberSaveable { mutableStateOf("Personal sideload mode") }
        var importedChats by remember { mutableStateOf<List<ImportedChat>>(emptyList()) }
        val automations = remember { mutableStateListOf<Automation>() }
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        val chatExportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) {
                taskStatus = "ChatGPT export selection cancelled."
            } else {
                taskStatus = "Importing ChatGPT conversations..."
                scope.launch {
                    try {
                        importedChats = importChatGPTExport(contentResolver, uri)
                        taskStatus = "Imported " + importedChats.size + " ChatGPT conversations."
                    } catch (error: Exception) {
                        taskStatus = error.message ?: "Could not import ChatGPT export."
                    }
                }
            }
        }

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
                    taskOutput = requestTaskPlan(apiKey, apiBaseUrl, selectedModel, trimmed)
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
                    val models = requestApiModels(apiKey, apiBaseUrl)
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

        fun openOfficialChatGPT() {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com")))
            taskStatus = "Opening official ChatGPT."
        }

        fun shareTaskToChatGPT(description: String) {
            val trimmed = description.trim()
            if (trimmed.isEmpty()) {
                taskStatus = "Describe a task before sending it to ChatGPT."
                return
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, trimmed)
            }
            startActivity(Intent.createChooser(shareIntent, "Send task to ChatGPT"))
            taskStatus = "Task ready to send to ChatGPT."
        }

        Scaffold { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(padding)
                    .safeDrawingPadding()
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
                ScrollableTabRow(selectedTabIndex = tab) {
                    listOf("Chat", "Automations", "Models", "ChatGPT", "Settings").forEachIndexed { index, label ->
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
                        onShareToChatGPT = { shareTaskToChatGPT(it) },
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
                    3 -> ChatGPTScreen(
                        chats = importedChats,
                        onOpenChatGPT = { openOfficialChatGPT() },
                        onImportExport = {
                            chatExportLauncher.launch(arrayOf("application/zip", "application/json", "text/*", "*/*"))
                        }
                    )
                    else -> SettingsScreen(
                        connectedProvider = connectedProvider,
                        apiBaseUrl = apiBaseUrl,
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
                onSave = { provider, baseUrl, key ->
                    connectedProvider = provider
                    apiBaseUrl = baseUrl
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
    onShareToChatGPT: (String) -> Unit,
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
        OutlinedButton(onClick = { onShareToChatGPT(command) }) {
            Text("Send task to ChatGPT")
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
            "Plan task and Run task now use the selected model, API key, and base URL. Device-control actions still require Android accessibility and an approved action executor.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ChatGPTScreen(
    chats: List<ImportedChat>,
    onOpenChatGPT: () -> Unit,
    onImportExport: () -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    val filteredChats = chats.filter {
        search.isBlank() ||
            it.title.contains(search, ignoreCase = true) ||
            it.transcript.contains(search, ignoreCase = true)
    }
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ChatGPT", style = MaterialTheme.typography.titleLarge)
        Text(
            "Use your ChatGPT subscription in the official ChatGPT app or website without putting an API key in WatchLearn AI."
        )
        Button(onClick = onOpenChatGPT) { Text("Open official ChatGPT") }
        OutlinedButton(onClick = onImportExport) { Text("Import ChatGPT export") }
        Text(
            "To import chats, request Export data in ChatGPT Settings → Data controls, then choose the ZIP file here."
        )
        if (chats.isNotEmpty()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search imported chats") },
                singleLine = true
            )
            Text("Imported chats: " + filteredChats.size)
            filteredChats.forEach { chat ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(chat.title, style = MaterialTheme.typography.titleMedium)
                        Text(chat.transcript.take(800))
                    }
                }
            }
        } else {
            Card {
                Text(
                    "No exported chats imported yet.",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        Text(
            "Sign in with ChatGPT does not give a third-party app automatic access to live ChatGPT conversations. This section uses the official ChatGPT launcher and your own data export instead.",
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
    var manualModel by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("API models", style = MaterialTheme.typography.titleLarge)
        Text(
            "Refresh loads every model returned by the connected provider. You can also enter any model ID supported by your API endpoint."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRefresh, enabled = !isRefreshing) {
                Text(if (isRefreshing) "Refreshing…" else "Refresh models")
            }
        }
        OutlinedTextField(
            value = manualModel,
            onValueChange = { manualModel = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enter any model ID") },
            singleLine = true
        )
        Button(
            onClick = { onSelect(manualModel.trim()) },
            enabled = manualModel.isNotBlank()
        ) {
            Text("Use model ID")
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
    apiBaseUrl: String,
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
        Text("API base URL: $apiBaseUrl")
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
    onSave: (String, String, String) -> Unit
) {
    var provider by rememberSaveable { mutableStateOf("OpenAI") }
    var baseUrl by rememberSaveable { mutableStateOf("https://api.openai.com/v1") }
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
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API base URL") },
                    supportingText = { Text("Example: https://api.openai.com/v1") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text("Use an OpenAI-compatible provider. The key stays in memory for this personal sideload session and is sent only over HTTPS.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(provider.trim(), baseUrl.trim().trimEnd('/'), key.trim()) },
                enabled = provider.isNotBlank() && baseUrl.isNotBlank() && key.isNotBlank()
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

private suspend fun requestApiModels(apiKey: String, baseUrl: String): List<String> =
    withContext(Dispatchers.IO) {
        val connection = (URL(apiEndpoint(baseUrl, "/models")).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer " + apiKey)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(friendlyApiError(code, body))
            val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until data.length()) {
                    val id = data.optJSONObject(index)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }.distinct().sorted()
        } finally {
            connection.disconnect()
        }
    }

private suspend fun requestTaskPlan(
    apiKey: String,
    baseUrl: String,
    model: String,
    description: String
): String = withContext(Dispatchers.IO) {
    val connection = (URL(apiEndpoint(baseUrl, "/chat/completions")).openConnection() as HttpURLConnection)
    try {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are WatchLearn AI. Create a concise, safe action plan for this Android task. " +
                            "Do not claim you performed actions. Identify every step that needs user permission."
                    )
            )
            .put(JSONObject().put("role", "user").put("content", description))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", "Bearer " + apiKey)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IOException(friendlyApiError(code, body))

        val json = JSONObject(body)
        val choices = json.optJSONArray("choices")
        val message = choices?.optJSONObject(0)?.optJSONObject("message")
        val content = message?.opt("content")
        when (content) {
            is String -> content
            null -> json.optString("output_text").ifBlank { body }
            else -> content.toString()
        }
    } finally {
        connection.disconnect()
    }
}

private fun apiEndpoint(baseUrl: String, suffix: String): String =
    baseUrl.trim().trimEnd('/') + suffix

private fun friendlyApiError(code: Int, body: String): String {
    val lower = body.lowercase()
    if (code == 429 && (lower.contains("credit_balance_exhausted") || lower.contains("insufficient_quota") || lower.contains("no credits"))) {
        return "This API key has no remaining credits. Add credits to that provider or connect a different API key."
    }
    val message = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("")
    return "API request failed ($code): " + message.ifBlank { body.take(320) }
}

private suspend fun importChatGPTExport(
    contentResolver: ContentResolver,
    uri: Uri
): List<ImportedChat> = withContext(Dispatchers.IO) {
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IOException("Could not read the selected ChatGPT export.")
    val jsonText = if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var conversations: ByteArray? = null
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith("conversations.json")) {
                    conversations = zip.readBytes()
                    break
                }
                entry = zip.nextEntry
            }
            conversations?.toString(Charsets.UTF_8)
                ?: throw IOException("The export ZIP does not contain conversations.json.")
        }
    } else {
        bytes.toString(Charsets.UTF_8)
    }
    parseChatGPTConversations(jsonText)
}

private fun parseChatGPTConversations(jsonText: String): List<ImportedChat> {
    val conversations = JSONArray(jsonText)
    return buildList {
        for (index in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(index) ?: continue
            val title = conversation.optString("title").ifBlank { "Untitled Chat" }
            val mapping = conversation.optJSONObject("mapping")
            val transcript = if (mapping == null) {
                ""
            } else {
                val messages = mutableListOf<String>()
                var nodeId = conversation.optString("current_node")
                while (nodeId.isNotBlank()) {
                    val node = mapping.optJSONObject(nodeId) ?: break
                    val message = node.optJSONObject("message")
                    val content = message?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null) {
                        val text = buildString {
                            for (partIndex in 0 until parts.length()) {
                                val part = parts.opt(partIndex)
                                if (part is String) append(part).append("\n")
                            }
                        }.trim()
                        if (text.isNotBlank()) messages.add(0, text)
                    }
                    nodeId = node.optString("parent")
                }
                messages.joinToString("\n\n")
            }
            add(ImportedChat(title, transcript.ifBlank { "No readable message text found." }))
        }
    }
}

