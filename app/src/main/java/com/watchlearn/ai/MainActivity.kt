package com.watchlearn.ai

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    // Current general-purpose/chat models shown by the OpenAI model catalog.
    "gpt-6-astra",
    "gpt-6.1-sol",
    "gpt-6.1-terra",
    "gpt-6-luna",
    "gpt-6-sol",
    "gpt-5.6-sol",
    "gpt-5.6-terra",
    "gpt-5.6-luna",
    "gpt-5.5",
    "gpt-5.5-pro",
    "gpt-5.4",
    "gpt-5.4-pro",
    "gpt-5.4-mini",
    "gpt-5.2",
    "gpt-5.2-pro",
    "gpt-5.3-codex",
    // Older compatible IDs are retained for providers that still expose them.
    "gpt-5",
    "gpt-5-mini",
    "gpt-5-nano",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4o-mini",
    "o3"
)

private fun modelDisplayName(modelId: String): String = modelId
    .split('-')
    .joinToString(" ") { part ->
        when {
            part.equals("gpt", ignoreCase = true) -> "GPT"
            part.equals("codex", ignoreCase = true) -> "Codex"
            part.equals("pro", ignoreCase = true) -> "Pro"
            part.length == 1 -> part.uppercase()
            else -> part.replaceFirstChar { it.uppercase() }
        }
    }

private val REASONING_EFFORT_OPTIONS = listOf(
    "Auto",
    "None",
    "Minimal",
    "Low",
    "Medium",
    "High",
    "XHigh",
    "Max"
)

private val REASONING_MODE_OPTIONS = listOf("Standard", "Pro")

private const val DEVICE_ACTION_INSTRUCTIONS = """
Return only valid JSON with this exact shape:
{"summary":"short explanation","actions":[{"type":"launch_app","package":"Chrome","label":"Open Chrome"}]}
Allowed action types are launch_app, open_browser, open_url, tap_text, tap_content_description, tap_view_id, tap, type_text, clear_text, swipe, back, home, recents, notifications, and wait.
Use launch_app with an app name when the user asks to open an app. In personal sideload mode, use open_browser for the WatchLearn AI Browser and include a URL when one was requested. Use open_url with a web address when the user asks to go to a website; include the target app in package when one was requested, for example {"type":"open_url","url":"https://google.com","package":"Chrome","label":"Open Google in Chrome"}. Use tap_text for visible labels, type_text for the currently focused field, and tap/swipe only when exact screen coordinates are known. For a webpage button, use tap_text with the exact visible button label or tap_content_description with the exact accessible label; do not guess coordinates when a visible label is available. The accessibility executor searches visible text, content descriptions, hints, clickable parents, and button bounds.
When the user asks to search Google for something, prefer one open_url action using https://www.google.com/search?q= followed by the URL-encoded search text, with package set to Chrome when Chrome was requested. For other apps, use tap_text or tap_content_description to focus the field, then type_text with label identifying the field, and tap_text or tap_content_description to press the visible Search/Submit button.
When a task names both Chrome and a URL, do not return only launch_app: include open_url with package Chrome immediately after it, or use open_url as the first action. Translate every requested step into a separate action. Add wait actions after launching a browser, opening a URL, opening a file picker, or pressing a button when the next control needs time to appear. If the user says the WatchLearn AI Browser is already open, never add open_browser, launch_app for WatchLearn AI Browser, or an unnecessary open_url before a button action; interact with the current page. If the user gives a duration such as "for 3 seconds," represent it with a wait action using duration_ms:3000. For websites and surveys, inspect the visible screen context before choosing controls, use the exact visible labels or content descriptions, scroll when needed, and verify each field after typing. For Prime Opinion or similar surveys, use only verified truthful user information, never invent answers or bypass screening, CAPTCHA, anti-bot, or security checks, and stop for the user when information is unknown or a protected step appears. Do not claim a survey was completed unless the final result is visibly shown.
Do not claim that an action was completed. Actions are only proposed until the user approves them in the Android app.
Never include password, payment, account deletion, message sending, or other sensitive/destructive actions without clearly listing them in actions for the user to review.
"""

private const val PLAY_STORE_ACTION_INSTRUCTIONS = """
Return a normal helpful response. This Play Store build does not have phone-control actions enabled and must not return launch_app, open_url, tap_text, tap_content_description, tap_view_id, tap, type_text, clear_text, swipe, back, home, recents, notifications, or wait actions.
"""

private fun actionInstructions(phoneControlEnabled: Boolean): String =
    if (phoneControlEnabled) DEVICE_ACTION_INSTRUCTIONS else PLAY_STORE_ACTION_INSTRUCTIONS

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
        var showKeyDialog by remember { mutableStateOf(false) }
        var showAutomationDialog by remember { mutableStateOf(false) }
        var apiKey by rememberSaveable { mutableStateOf("") }
        var connectedProvider by rememberSaveable { mutableStateOf("") }
        var apiBaseUrl by rememberSaveable { mutableStateOf("https://api.openai.com/v1") }
        var chatGptSession by remember { mutableStateOf<ChatGptPlanSession?>(null) }
        var isChatGptSigningIn by remember { mutableStateOf(false) }
        var selectedModel by rememberSaveable { mutableStateOf(DEFAULT_OPENAI_MODELS.first()) }
        var availableModels by remember { mutableStateOf(DEFAULT_OPENAI_MODELS) }
        var selectedReasoningEffort by rememberSaveable { mutableStateOf("Auto") }
        var selectedReasoningMode by rememberSaveable { mutableStateOf("Standard") }
        var isRefreshingModels by remember { mutableStateOf(false) }
        var taskStatus by rememberSaveable { mutableStateOf("Ready for a task.") }
        var lastTaskDescription by rememberSaveable { mutableStateOf("") }
        var taskOutput by rememberSaveable { mutableStateOf("") }
        var pendingDevicePlan by remember { mutableStateOf<DevicePlan?>(null) }
        var showActionApproval by remember { mutableStateOf(false) }
        var pendingAppendPlan by remember { mutableStateOf<DevicePlan?>(null) }
        var showAppendApproval by remember { mutableStateOf(false) }
        var deploymentMode by rememberSaveable { mutableStateOf("Personal sideload mode") }
        var importedChats by remember { mutableStateOf<List<ImportedChat>>(emptyList()) }
        val automations = remember { mutableStateListOf<Automation>() }
        val scope = rememberCoroutineScope()
        val chatGptAuth = remember { ChatGptPlanAuth(this@MainActivity) }
        val scrollState = rememberScrollState()
        val phoneControlEnabled = BuildConfig.PERSONAL_SIDELOAD_MODE &&
            deploymentMode == "Personal sideload mode"
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
            chatGptSession = chatGptAuth.loadSession()
        }

        fun runTask(description: String) {
            val trimmed = description.trim()
            if (trimmed.isEmpty()) {
                taskStatus = "Describe a task before pressing Run task now."
                taskOutput = ""
                return
            }
            lastTaskDescription = trimmed
            if (apiKey.isBlank() && chatGptSession == null) {
                taskStatus = "Connect ChatGPT or an API key before running this task."
                taskOutput = "Open the ChatGPT tab and choose Continue with ChatGPT, or connect an API key in Settings."
                return
            }

            taskStatus = if (chatGptSession != null) {
                "Running task with your ChatGPT plan using $selectedModel ($selectedReasoningEffort)..."
            } else {
                "Running task with $selectedModel ($selectedReasoningEffort)..."
            }
            taskOutput = ""
            scope.launch {
                try {
                    val response = if (chatGptSession != null) {
                        requestChatGptPlan(
                            accessToken = chatGptSession!!.accessToken,
                            model = selectedModel,
                            description = trimmed,
                            reasoningEffort = selectedReasoningEffort,
                            reasoningMode = selectedReasoningMode,
                            phoneControlEnabled = phoneControlEnabled
                        )
                    } else {
                        requestTaskPlan(
                            apiKey = apiKey,
                            baseUrl = apiBaseUrl,
                            model = selectedModel,
                            description = trimmed,
                            reasoningEffort = selectedReasoningEffort,
                            phoneControlEnabled = phoneControlEnabled
                        )
                    }
                    val devicePlan = parseDevicePlan(response)
                    if (devicePlan != null) {
                        taskOutput = formatDevicePlan(devicePlan)
                        pendingDevicePlan = devicePlan.takeIf {
                            phoneControlEnabled && it.actions.isNotEmpty()
                        }
                        if (!phoneControlEnabled && devicePlan.actions.isNotEmpty()) {
                            taskStatus = "Phone control is disabled in Play Store mode."
                        } else if (pendingDevicePlan != null) {
                            if (WatchLearnAccessibilityService.isReady()) {
                                showActionApproval = true
                                taskStatus = "Phone-control plan ready. Review and approve the proposed actions."
                            } else {
                                taskStatus = "Phone-control plan ready. Enable Android accessibility before approving it."
                            }
                        } else {
                            taskStatus = "Plan completed with $selectedModel ($selectedReasoningEffort)."
                        }
                    } else {
                        taskOutput = response
                        taskStatus = "Plan completed with $selectedModel ($selectedReasoningEffort)."
                    }
                } catch (error: Exception) {
                    taskStatus = "Task could not run."
                    taskOutput = error.message ?: "OpenAI request failed."
                }
            }
        }

        fun retryLastTask() {
            if (lastTaskDescription.isBlank()) {
                taskStatus = "There is no previous task to retry."
                return
            }
            taskStatus = "Retrying the last task with the same instructions..."
            runTask(lastTaskDescription)
        }

        fun planBrowserTask(description: String, append: Boolean) {
            val trimmed = description.trim()
            if (trimmed.isEmpty()) {
                BrowserTaskBridge.publishMessage("Enter an instruction before submitting it.")
                return
            }
            if (!phoneControlEnabled) {
                BrowserTaskBridge.publishMessage("Browser task editing is available only in personal sideload mode.")
                return
            }
            if (append && !WatchLearnAccessibilityService.isRunning()) {
                BrowserTaskBridge.publishMessage("There is no running task to add to. Start and approve a task first.")
                return
            }
            if (!append && WatchLearnAccessibilityService.isRunning()) {
                BrowserTaskBridge.publishMessage("A task is already running. Add this instruction to it or wait until it finishes.")
                return
            }
            if (apiKey.isBlank() && chatGptSession == null) {
                BrowserTaskBridge.publishMessage("Connect ChatGPT or an API key in WatchLearn AI before editing a browser task.")
                return
            }

            BrowserTaskBridge.publishMessage(
                if (append) "Planning the new step to add to the running task..."
                else "Planning the edited browser task..."
            )
            val contextInstruction =
                "The user is already on the current webpage inside the WatchLearn AI Browser. " +
                    "Do not open, relaunch, reload, or restart the browser for this edit. " +
                    "Use the current visible page and interact with its visible controls. "
            scope.launch {
                try {
                    val response = if (chatGptSession != null) {
                        requestChatGptPlan(
                            accessToken = chatGptSession!!.accessToken,
                            model = selectedModel,
                            description = if (append) {
                                "$contextInstruction" +
                                    "This is an additional step for the currently running browser task. " +
                                    "Return only the new actions to append; do not repeat existing actions. " +
                                    trimmed
                            } else {
                                "$contextInstruction" +
                                    "Create a replacement task plan for the browser. " + trimmed
                            },
                            reasoningEffort = selectedReasoningEffort,
                            reasoningMode = selectedReasoningMode,
                            phoneControlEnabled = phoneControlEnabled
                        )
                    } else {
                        requestTaskPlan(
                            apiKey = apiKey,
                            baseUrl = apiBaseUrl,
                            model = selectedModel,
                            description = if (append) {
                                "$contextInstruction" +
                                    "This is an additional step for the currently running browser task. " +
                                    "Return only the new actions to append; do not repeat existing actions. " +
                                    trimmed
                            } else {
                                "$contextInstruction" +
                                    "Create a replacement task plan for the browser. " + trimmed
                            },
                            reasoningEffort = selectedReasoningEffort,
                            phoneControlEnabled = phoneControlEnabled
                        )
                    }
                    val plan = parseDevicePlan(response)
                    val safePlan = plan?.let { candidate ->
                        if (BrowserTaskBridge.isBrowserActive()) {
                            candidate.copy(
                                actions = candidate.actions.filterNot { action ->
                                    action.type == "open_browser" ||
                                        (
                                            action.type in setOf("launch_app", "open_app") &&
                                                action.packageName
                                                    .ifBlank { action.text }
                                                    .contains("WatchLearn", ignoreCase = true)
                                        )
                                }
                            )
                        } else {
                            candidate
                        }
                    }
                    if (safePlan == null || safePlan.actions.isEmpty()) {
                        BrowserTaskBridge.publishMessage(
                            "The AI did not return usable browser actions. " +
                                response.take(240)
                        )
                    } else {
                        taskOutput = formatDevicePlan(safePlan)
                        taskStatus = "Browser plan ready for your approval."
                        BrowserTaskBridge.publishPlan(safePlan, append)
                    }
                } catch (error: Exception) {
                    val reason = error.message ?: "The AI request failed."
                    taskStatus = "Browser task planning failed: " + reason
                    BrowserTaskBridge.publishMessage("Task planning failed: " + reason)
                }
            }
        }

        fun approveBrowserPlan(plan: DevicePlan, append: Boolean) {
            if (append) {
                val added = WatchLearnAccessibilityService.appendApproved(plan.actions)
                val message = if (added) {
                    "Added the approved steps to the running browser task."
                } else {
                    "The running task ended before the new steps could be added."
                }
                taskStatus = message
                BrowserTaskBridge.publishMessage(message)
            } else {
                val started = WatchLearnAccessibilityService.executeApproved(plan.actions) { success, message ->
                    taskStatus = if (success) message else "Browser task failed: " + message
                    BrowserTaskBridge.publishMessage(
                        if (success) message else "Task failed: " + message
                    )
                }
                val message = if (started) {
                    "Approved. The browser task is now running."
                } else {
                    "Android Accessibility is not enabled or another task is already running."
                }
                taskStatus = message
                BrowserTaskBridge.publishMessage(message)
            }
        }

        fun addToRunningTask(description: String) {
            val trimmed = description.trim()
            if (trimmed.isEmpty()) {
                taskStatus = "Describe the extra step before adding it."
                return
            }
            if (!phoneControlEnabled || !WatchLearnAccessibilityService.isRunning()) {
                taskStatus = "No approved phone task is currently running. Run and approve the main task first."
                return
            }
            if (apiKey.isBlank() && chatGptSession == null) {
                taskStatus = "Connect ChatGPT or an API key before planning an added step."
                return
            }
            taskStatus = "Planning the additional step..."
            scope.launch {
                try {
                    val response = if (chatGptSession != null) {
                        requestChatGptPlan(
                            accessToken = chatGptSession!!.accessToken,
                            model = selectedModel,
                            description = "This is an additional step for an already-running approved Android task. " +
                                "Return only the new actions to append; do not repeat or undo existing actions. " + trimmed,
                            reasoningEffort = selectedReasoningEffort,
                            reasoningMode = selectedReasoningMode,
                            phoneControlEnabled = phoneControlEnabled
                        )
                    } else {
                        requestTaskPlan(
                            apiKey = apiKey,
                            baseUrl = apiBaseUrl,
                            model = selectedModel,
                            description = "This is an additional step for an already-running approved Android task. " +
                                "Return only the new actions to append; do not repeat or undo existing actions. " + trimmed,
                            reasoningEffort = selectedReasoningEffort,
                            phoneControlEnabled = phoneControlEnabled
                        )
                    }
                    val plan = parseDevicePlan(response)
                    if (plan == null || plan.actions.isEmpty()) {
                        taskStatus = "The added request did not produce phone actions."
                        taskOutput = response
                    } else {
                        pendingAppendPlan = plan
                        taskOutput = "Proposed additions:\n\n" + formatDevicePlan(plan)
                        showAppendApproval = true
                        taskStatus = "Review the proposed additions before appending them."
                    }
                } catch (error: Exception) {
                    taskStatus = "The added request could not be planned."
                    taskOutput = error.message ?: "OpenAI request failed."
                }
            }
        }

        fun refreshModels() {
            val bearerToken = apiKey.ifBlank { chatGptSession?.accessToken.orEmpty() }
            if (bearerToken.isBlank()) {
                taskStatus = "Connect ChatGPT or an API key before refreshing models."
                return
            }

            // A Sign in with ChatGPT session is not an API organization key and
            // may not be allowed to enumerate /v1/models. Keep the built-in
            // catalog available instead of showing an empty model screen.
            if (apiKey.isBlank() && chatGptSession != null) {
                availableModels = DEFAULT_OPENAI_MODELS
                if (selectedModel !in availableModels) selectedModel = availableModels.first()
                taskStatus = "Loaded the built-in ChatGPT model catalog. Connect an API key to verify models enabled for your API organization."
                return
            }

            isRefreshingModels = true
            taskStatus = "Loading models available to the connected provider..."
            scope.launch {
                try {
                    val models = requestApiModels(bearerToken, apiBaseUrl)
                    if (models.isNotEmpty()) {
                        // Keep the catalog useful even when a provider's
                        // /models endpoint returns only a partial list.
                        availableModels = (DEFAULT_OPENAI_MODELS + models).distinct()
                        if (selectedModel !in availableModels) selectedModel = models.first()
                        taskStatus = "Loaded " + availableModels.size + " models, including the provider's returned models."
                    } else {
                        availableModels = DEFAULT_OPENAI_MODELS
                        if (selectedModel !in availableModels) selectedModel = availableModels.first()
                        taskStatus = "The provider returned no model list, so the built-in ChatGPT model catalog is being used."
                    }
                } catch (error: Exception) {
                    availableModels = DEFAULT_OPENAI_MODELS
                    if (selectedModel !in availableModels) selectedModel = availableModels.first()
                    taskStatus = "Model refresh unavailable; using the built-in catalog. " +
                        (error.message ?: "Connect an API key with model-list access to verify availability.")
                } finally {
                    isRefreshingModels = false
                }
            }
        }

        fun openOfficialChatGPT() {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com")))
            taskStatus = "Opening official ChatGPT."
        }

        fun openInAppBrowser() {
            if (!BuildConfig.PERSONAL_SIDELOAD_MODE) {
                taskStatus = "The in-app browser is available only in personal sideload mode."
                return
            }
            startActivity(
                Intent().setClassName(
                    packageName,
                    "com.watchlearn.ai.InAppBrowserActivity"
                )
            )
            taskStatus = "Opened WatchLearn AI Browser."
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

        val currentBrowserRequestHandler = rememberUpdatedState<(String, Boolean) -> Unit>(
            { text, append -> planBrowserTask(text, append) }
        )
        val currentBrowserApprovalHandler = rememberUpdatedState<(DevicePlan, Boolean) -> Unit>(
            { plan, append -> approveBrowserPlan(plan, append) }
        )
        DisposableEffect(Unit) {
            BrowserTaskBridge.registerTaskHandlers(
                requestHandler = { text, append ->
                    currentBrowserRequestHandler.value(text, append)
                },
                approvalHandler = { plan, append ->
                    currentBrowserApprovalHandler.value(plan, append)
                }
            )
            onDispose { BrowserTaskBridge.clearTaskHandlers() }
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
                    (listOf("Chat", "Automations", "Models", "ChatGPT", "Settings") + if (BuildConfig.PERSONAL_SIDELOAD_MODE) listOf("Browser") else emptyList()).forEachIndexed { index, label ->
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
                        selectedReasoningEffort = selectedReasoningEffort,
                        taskStatus = taskStatus,
                        taskOutput = taskOutput,
                        onRunTask = { runTask(it) },
                        onRetryTask = { retryLastTask() },
                        onPauseTask = {
                            taskStatus = if (WatchLearnAccessibilityService.pauseApproved()) {
                                "Task paused. Nothing else will run until you tap Resume task."
                            } else {
                                "No approved phone task is currently running."
                            }
                        },
                        onResumeTask = {
                            taskStatus = if (WatchLearnAccessibilityService.resumeApproved()) {
                                "Task resumed."
                            } else {
                                "No paused phone task is available to resume."
                            }
                        },
                        onAddTask = { addToRunningTask(it) },
                        onShareToChatGPT = { shareTaskToChatGPT(it) },
                        onAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            taskStatus = "Android accessibility settings opened."
                        },
                        phoneControlEnabled = phoneControlEnabled
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
                        selectedReasoningEffort = selectedReasoningEffort,
                        selectedReasoningMode = selectedReasoningMode,
                        isRefreshing = isRefreshingModels,
                        onSelect = {
                            selectedModel = it
                            taskStatus = "Selected model: $it"
                        },
                        onReasoningEffortSelect = {
                            selectedReasoningEffort = it
                            taskStatus = "Selected reasoning effort: $it"
                        },
                        onReasoningModeSelect = {
                            selectedReasoningMode = it
                            taskStatus = "Selected reasoning mode: $it"
                        },
                        onRefresh = { refreshModels() }
                    )
                    3 -> ChatGPTScreen(
                        chats = importedChats,
                        connected = chatGptSession != null,
                        isSigningIn = isChatGptSigningIn,
                        onSignIn = {
                            if (!isChatGptSigningIn) {
                                isChatGptSigningIn = true
                                taskStatus = "Opening secure ChatGPT sign-in..."
                                scope.launch {
                                    try {
                                        chatGptSession = chatGptAuth.signIn()
                                        taskStatus = "ChatGPT connected. Plan task can now use your ChatGPT allowance."
                                    } catch (error: Exception) {
                                        taskStatus = error.message ?: "ChatGPT sign-in failed."
                                    } finally {
                                        isChatGptSigningIn = false
                                    }
                                }
                            }
                        },
                        onSignOut = {
                            chatGptAuth.clear()
                            chatGptSession = null
                            taskStatus = "ChatGPT disconnected from WatchLearn AI."
                        },
                        onOpenChatGPT = { openOfficialChatGPT() },
                        onImportExport = {
                            chatExportLauncher.launch(arrayOf("application/zip", "application/json", "text/*", "*/*"))
                        }
                    )
                    4 -> SettingsScreen(
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
                    5 -> BrowserEntryScreen(
                        onOpenBrowser = { openInAppBrowser() },
                        phoneControlEnabled = phoneControlEnabled
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
        if (showActionApproval) {
            val plan = pendingDevicePlan
            if (plan != null) {
                AlertDialog(
                    onDismissRequest = {
                        showActionApproval = false
                        pendingDevicePlan = null
                        taskStatus = "Phone-control actions were not approved."
                    },
                    title = { Text("Approve phone control?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(plan.summary)
                            plan.actions.forEachIndexed { index, action ->
                                Text("${index + 1}. ${describeDeviceAction(action)}")
                            }
                            Text(
                                "WatchLearn AI will perform these actions through Android Accessibility. Review them before continuing.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showActionApproval = false
                            val started = WatchLearnAccessibilityService.executeApproved(plan.actions) { success, message ->
                                taskStatus = if (success) {
                                    message
                                } else {
                                    "Phone control stopped: $message"
                                }
                                if (!success) taskOutput = taskOutput + "\n\n" + message
                                pendingDevicePlan = null
                            }
                            if (!started) {
                                pendingDevicePlan = null
                                taskStatus = "Android accessibility is not enabled. Enable it, then run the task again."
                            } else {
                                taskStatus = "Approved. Performing phone actions..."
                            }
                        }) { Text("Approve and run") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showActionApproval = false
                            pendingDevicePlan = null
                            taskStatus = "Phone-control actions were not approved."
                        }) { Text("Cancel") }
                    }
                )
            }
        }
        if (showAppendApproval) {
            val plan = pendingAppendPlan
            if (plan != null) {
                AlertDialog(
                    onDismissRequest = {
                        showAppendApproval = false
                        pendingAppendPlan = null
                        taskStatus = "The additional actions were not added."
                    },
                    title = { Text("Add actions to running task?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(plan.summary)
                            plan.actions.forEachIndexed { index, action ->
                                Text("${index + 1}. ${describeDeviceAction(action)}")
                            }
                            Text(
                                "These actions will be appended after the current step. Review them before continuing.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAppendApproval = false
                            val added = WatchLearnAccessibilityService.appendApproved(plan.actions)
                            pendingAppendPlan = null
                            taskStatus = if (added) {
                                "Additional actions added to the running task."
                            } else {
                                "The task finished before the additional actions could be added."
                            }
                        }) { Text("Add and continue") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showAppendApproval = false
                            pendingAppendPlan = null
                            taskStatus = "The additional actions were not added."
                        }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
private fun BrowserEntryScreen(
    onOpenBrowser: () -> Unit,
    phoneControlEnabled: Boolean
) {
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("In-app browser", style = MaterialTheme.typography.titleLarge)
        if (phoneControlEnabled) {
            Text(
                "Use a normal browser inside WatchLearn AI. It supports website navigation, JavaScript, cookies, file uploads, downloads, and browser back/forward controls. The personal Accessibility Service can also interact with visible webpage controls after you approve an AI task."
            )
            Button(onClick = onOpenBrowser) {
                Text("Open WatchLearn Browser")
            }
            Text(
                "For AI tasks, ask: “Open the WatchLearn AI Browser and go to a website.” Then review and approve the proposed actions.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                "The in-app browser is disabled in Play Store mode. Use the personal sideload build for this feature."
            )
        }
    }
}

@Composable
private fun ChatScreen(
    selectedModel: String,
    selectedReasoningEffort: String,
    taskStatus: String,
    taskOutput: String,
    onRunTask: (String) -> Unit,
    onRetryTask: () -> Unit,
    onPauseTask: () -> Unit,
    onResumeTask: () -> Unit,
    onAddTask: (String) -> Unit,
    onShareToChatGPT: (String) -> Unit,
    onAccessibility: () -> Unit,
    phoneControlEnabled: Boolean
) {
    var command by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Describe a task, choose a model, and run it immediately.")
        Text(
            "Selected model: $selectedModel · Reasoning: $selectedReasoningEffort",
            style = MaterialTheme.typography.labelLarge
        )
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What should I do?") },
            minLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onRunTask(command) }) { Text("Run task now") }
            OutlinedButton(
                onClick = onRetryTask,
                enabled = command.isNotBlank()
            ) { Text("Try again") }
        }
        if (phoneControlEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPauseTask) { Text("Pause task") }
                OutlinedButton(onClick = onResumeTask) { Text("Resume task") }
            }
            OutlinedButton(
                onClick = { onAddTask(command) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit or add to running task")
            }
            OutlinedButton(onClick = onAccessibility) {
                Text("Enable Android accessibility")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("How to use Run Task", style = MaterialTheme.typography.titleSmall)
                    Text("1. Enter what you want WatchLearn AI to do.")
                    Text("2. Choose your model and reasoning level in Models.")
                    Text("3. Tap Run task now.")
                    Text("4. Review the proposed actions and approve them.")
                    Text("5. Watch the compact task status panel while it works.")
                    Text("6. If it fails, read the reason, edit the request, or tap Try again.")
                    Text("Enable Android Accessibility first for tasks that use other apps.")
                }
            }
        }
        OutlinedButton(onClick = { onShareToChatGPT(command) }) {
            Text("Send task to ChatGPT")
        }
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Task status", style = MaterialTheme.typography.titleSmall)
                Text(taskStatus)
                if (taskOutput.isNotBlank()) {
                    Text(taskOutput)
                }
            }
        }
        Text(
            if (phoneControlEnabled) {
                "Personal sideload mode can propose phone actions, pause or resume an approved task, and append a newly approved action plan while it runs. Android accessibility must be enabled, and you must approve each proposed plan."
            } else {
                "Play Store mode can plan tasks but does not include cross-app phone control or Accessibility Service automation."
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ChatGPTScreen(
    chats: List<ImportedChat>,
    connected: Boolean,
    isSigningIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
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
        Text(
            if (connected) {
                "ChatGPT plan connected. Plan task can use your authorized ChatGPT allowance."
            } else {
                "ChatGPT plan is not connected."
            },
            color = MaterialTheme.colorScheme.primary
        )
        Button(onClick = onSignIn, enabled = !connected && !isSigningIn) {
            Text(if (isSigningIn) "Waiting for ChatGPT…" else "Continue with ChatGPT")
        }
        if (connected) {
            OutlinedButton(onClick = onSignOut) { Text("Disconnect ChatGPT") }
        }
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
            "The connection can authorize plan requests, but it does not provide live ChatGPT conversation history. Use the export importer below for your chats.",
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
    selectedReasoningEffort: String,
    selectedReasoningMode: String,
    isRefreshing: Boolean,
    onSelect: (String) -> Unit,
    onReasoningEffortSelect: (String) -> Unit,
    onReasoningModeSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var manualModel by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("API models", style = MaterialTheme.typography.titleLarge)
        Text(
            "The built-in catalog includes the current general-purpose chat models, including GPT-5.6 Luna. Refresh adds models returned by your API organization. A ChatGPT sign-in may not expose the API model-list endpoint, so it falls back to this catalog."
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
        Text("Reasoning effort", style = MaterialTheme.typography.titleMedium)
        Text(
            "Auto lets the model choose. Some models support only a subset of these values; if a provider rejects a value, choose Auto or another supported level.",
            style = MaterialTheme.typography.bodySmall
        )
        REASONING_EFFORT_OPTIONS.forEach { effort ->
            FilterChip(
                selected = selectedReasoningEffort == effort,
                onClick = { onReasoningEffortSelect(effort) },
                label = { Text(effort) }
            )
        }
        Text("Reasoning mode", style = MaterialTheme.typography.titleMedium)
        REASONING_MODE_OPTIONS.forEach { mode ->
            FilterChip(
                selected = selectedReasoningMode == mode,
                onClick = { onReasoningModeSelect(mode) },
                label = { Text(mode) }
            )
        }
        models.forEach { model ->
            FilterChip(
                selected = selectedModel == model,
                onClick = { onSelect(model) },
                label = { Text(modelDisplayName(model)) }
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

private suspend fun requestChatGptPlan(
    accessToken: String,
    model: String,
    description: String,
    reasoningEffort: String,
    reasoningMode: String,
    phoneControlEnabled: Boolean
): String = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection)
    try {
        val input = JSONArray().put(
            JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", description)
                    )
                )
        )
        val reasoning = JSONObject()
        if (!reasoningEffort.equals("Auto", ignoreCase = true)) {
            reasoning.put("effort", reasoningEffort.lowercase())
        }
        if (reasoningMode.equals("Pro", ignoreCase = true)) {
            reasoning.put("mode", "pro")
        }
        val payload = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("stream", true)
            .put(
                "instructions",
                "You are WatchLearn AI. Create a concise, safe action plan for this Android task. " +
                    "Do not claim you performed actions. Identify every step that needs user permission.\n" +
                    actionInstructions(phoneControlEnabled)
            )
            .put("input", input)
        if (reasoning.length() > 0) payload.put("reasoning", reasoning)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", "Bearer " + accessToken)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { reader ->
            if (code !in 200..299) {
                reader.readText()
            } else {
                val output = StringBuilder()
                reader.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data != "[DONE]" && data.isNotBlank()) {
                            runCatching {
                                val event = JSONObject(data)
                                if (event.optString("type") == "response.output_text.delta") {
                                    output.append(event.optString("delta"))
                                }
                                if (event.optString("type") == "response.failed") {
                                    throw IOException(event.optJSONObject("error")?.optString("message"))
                                }
                            }.getOrThrow()
                        }
                    }
                }
                output.toString()
            }
        }.orEmpty()
        if (code !in 200..299) throw IOException(friendlyApiError(code, body))
        body.ifBlank { "ChatGPT returned no text." }
    } finally {
        connection.disconnect()
    }
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
    description: String,
    reasoningEffort: String,
    phoneControlEnabled: Boolean
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
                            "Do not claim you performed actions. Identify every step that needs user permission.\n" +
                            actionInstructions(phoneControlEnabled)
                    )
            )
            .put(JSONObject().put("role", "user").put("content", description))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
        if (!reasoningEffort.equals("Auto", ignoreCase = true)) {
            payload.put("reasoning_effort", reasoningEffort.lowercase())
        }
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

