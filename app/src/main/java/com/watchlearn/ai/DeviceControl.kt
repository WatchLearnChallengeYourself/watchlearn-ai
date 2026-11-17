package com.watchlearn.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

data class DeviceAction(
    val type: String,
    val label: String = "",
    val text: String = "",
    val url: String = "",
    val packageName: String = "",
    val viewId: String = "",
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Long = 500L
)

data class DevicePlan(
    val summary: String,
    val actions: List<DeviceAction>
)

fun parseDevicePlan(response: String): DevicePlan? {
    val start = response.indexOf('{')
    val end = response.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        val root = org.json.JSONObject(response.substring(start, end + 1))
        val actionsJson = root.optJSONArray("actions") ?: return@runCatching null
        val actions = buildList {
            for (index in 0 until actionsJson.length()) {
                val item = actionsJson.optJSONObject(index) ?: continue
                val type = item.optString("type").trim().lowercase(Locale.US)
                if (type.isBlank()) continue
                add(
                    DeviceAction(
                        type = type,
                        label = item.optString("label"),
                        text = item.optString("text", item.optString("value")),
                        url = item.optString("url", item.optString("uri")),
                        packageName = item.optString("package", item.optString("app")),
                        viewId = item.optString("view_id", item.optString("viewId")),
                        x = item.optionalFloat("x"),
                        y = item.optionalFloat("y"),
                        x2 = item.optionalFloat("x2"),
                        y2 = item.optionalFloat("y2"),
                        durationMs = item.optLong("duration_ms", item.optLong("durationMs", 500L))
                    )
                )
            }
        }
        DevicePlan(
            summary = root.optString("summary").ifBlank { "The model prepared phone actions." },
            actions = actions
        )
    }.getOrNull()
}

private fun org.json.JSONObject.optionalFloat(name: String): Float? =
    if (has(name) && !isNull(name)) optDouble(name).toFloat() else null

fun formatDevicePlan(plan: DevicePlan): String = buildString {
    append(plan.summary)
    if (plan.actions.isNotEmpty()) {
        append("\n\nProposed phone actions:")
        plan.actions.forEachIndexed { index, action ->
            append("\n")
            append(index + 1)
            append(". ")
            append(describeDeviceAction(action))
        }
    }
}

fun describeDeviceAction(action: DeviceAction): String = when (action.type) {
    "launch_app", "open_app" -> "Open ${action.packageName.ifBlank { action.text.ifBlank { "the app" } }}"
    "open_browser" -> "Open WatchLearn AI Browser" + action.url.takeIf { it.isNotBlank() }?.let { " at " + it }.orEmpty()
    "open_url" -> "Open ${action.url.ifBlank { action.text }}${action.packageName.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""}"
    "tap_text" -> "Tap “${action.text}”"
    "tap_content_description" -> "Tap control described as “${action.text}”"
    "tap_view_id" -> "Tap view ${action.viewId}"
    "tap" -> "Tap screen at (${action.x ?: 0f}, ${action.y ?: 0f})"
    "type_text" -> "Type “${action.text}”"
    "clear_text" -> "Clear the selected text field"
    "swipe" -> "Swipe on the screen"
    "back" -> "Press Back"
    "home" -> "Go Home"
    "recents" -> "Open Recents"
    "notifications" -> "Open notifications"
    "wait" -> "Wait ${action.durationMs} ms"
    else -> "Unsupported action: ${action.type}"
}

class WatchLearnAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var paused = false
    private var queuedActions = mutableListOf<DeviceAction>()
    private var nextActionIndex = 0
    private var completionCallback: ((Boolean, String) -> Unit)? = null
    private var executionGeneration = 0L
    private var lastApprovedActions: List<DeviceAction> = emptyList()
    @Volatile private var actionStatus = "No approved task is running."
    @Volatile private var failureReason = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun execute(actions: List<DeviceAction>, callback: (Boolean, String) -> Unit) {
        handler.post {
            if (running) {
                callback(false, "A phone task is already running.")
                return@post
            }
            queuedActions = actions.toMutableList()
            lastApprovedActions = actions.toList()
            nextActionIndex = 0
            paused = false
            running = true
            failureReason = ""
            actionStatus = "Preparing the approved task..."
            executionGeneration += 1
            completionCallback = callback
            continueExecution()
        }
    }

    fun pauseTask(): Boolean {
        if (!running) return false
        handler.post {
            paused = true
            actionStatus = "Task paused."
            executionGeneration += 1
        }
        return true
    }

    fun resumeTask(): Boolean {
        if (!running) return false
        handler.post {
            paused = false
            actionStatus = "Resuming the task..."
            executionGeneration += 1
            continueExecution()
        }
        return true
    }

    fun appendTask(actions: List<DeviceAction>): Boolean {
        if (!running || actions.isEmpty()) return false
        handler.post {
            if (running) {
                queuedActions.addAll(actions)
                actionStatus = "Added " + actions.size + " action(s) to the running task."
            }
        }
        return true
    }

    fun stopTask(): Boolean {
        if (!running) return false
        handler.post {
            executionGeneration += 1
            finish(false, "Phone task stopped by the user.")
        }
        return true
    }

    private fun continueExecution() {
        if (!running || paused) return
        if (nextActionIndex >= queuedActions.size) {
            finish(true, "Approved phone actions completed.")
            return
        }
        val action = queuedActions[nextActionIndex]
        actionStatus = "Doing: " + describeDeviceAction(action)
        val generation = executionGeneration
        runActionWithRetry(action, 0, generation) { result ->
            if (!running) return@runActionWithRetry
            if (!result.first) {
                finish(false, "${describeDeviceAction(action)} failed after waiting for the screen: ${result.second}")
                return@runActionWithRetry
            }
            nextActionIndex += 1
            val delay = when (action.type) {
                "launch_app", "open_app", "open_browser", "open_url", "back", "home", "recents", "notifications" -> 1_200L
                "wait" -> action.durationMs.coerceIn(0L, 30_000L)
                else -> 500L
            }
            handler.postDelayed({
                if (generation == executionGeneration) continueExecution()
            }, delay)
        }
    }

    private fun finish(success: Boolean, message: String) {
        if (!running) return
        running = false
        paused = false
        executionGeneration += 1
        actionStatus = if (success) message else "Failed: " + message
        failureReason = if (success) "" else message
        queuedActions.clear()
        nextActionIndex = 0
        val callback = completionCallback
        completionCallback = null
        callback?.invoke(success, message)
    }

    /**
     * Chrome and file-picker accessibility trees often need a few seconds to
     * appear after an app launch or navigation. A single failed lookup used to
     * stop the entire approved plan, which looked like "Chrome opened and then
     * nothing happened." Retry only UI-dependent actions.
     */
    private fun runActionWithRetry(
        action: DeviceAction,
        attempt: Int,
        generation: Long,
        callback: (Pair<Boolean, String>) -> Unit
    ) {
        if (!running || paused || generation != executionGeneration) return
        val result = runAction(action)
        val retryable = action.type in setOf(
            "tap_text",
            "tap_content_description",
            "tap_view_id",
            "tap",
            "type_text",
            "clear_text",
            "swipe"
        )
        if (result.first || !retryable || attempt >= 8) {
            callback(result)
            return
        }
        handler.postDelayed({
            runActionWithRetry(action, attempt + 1, generation, callback)
        }, 750L)
    }

    private fun runAction(action: DeviceAction): Pair<Boolean, String> {
        return when (action.type) {
            "launch_app", "open_app" -> {
                val requestedApp = action.packageName.ifBlank { action.text }
                if (requestedApp.equals("WatchLearn Browser", ignoreCase = true) ||
                    requestedApp.equals("WatchLearn AI Browser", ignoreCase = true)
                ) {
                    return openBrowser(action)
                }
                val packageName = resolvePackage(requestedApp)
                    ?: return false to "Could not find an installed app named “" + requestedApp + "”. "
                val launchIntent = launchIntentForPackage(packageName)
                    ?: return false to "The app is installed but has no launchable activity: " + packageName
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                true to "Opened " + packageName
            }
            "open_browser" -> openBrowser(action)
            "open_url" -> openUrl(action)
            "tap_text" -> tapText(action.text)
            "tap_content_description" -> tapContentDescription(action.text)
            "tap_view_id" -> tapViewId(action.viewId)
            "tap" -> {
                val x = action.x ?: return false to "Tap action is missing x."
                val y = action.y ?: return false to "Tap action is missing y."
                dispatchTap(x, y)
            }
            "type_text" -> typeText(action)
            "clear_text" -> clearText(action)
            "swipe" -> {
                val x1 = action.x ?: return false to "Swipe action is missing x."
                val y1 = action.y ?: return false to "Swipe action is missing y."
                val x2 = action.x2 ?: return false to "Swipe action is missing x2."
                val y2 = action.y2 ?: return false to "Swipe action is missing y2."
                dispatchSwipe(x1, y1, x2, y2, action.durationMs)
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK) to "Pressed Back"
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME) to "Went Home"
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS) to "Opened Recents"
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) to "Opened notifications"
            "wait" -> true to "Waiting"
            else -> false to "Unsupported phone action: " + action.type
        }
    }

    private fun openBrowser(action: DeviceAction): Pair<Boolean, String> {
        val requestedUrl = action.url.ifBlank { action.text }.trim()
        val intent = if (requestedUrl.isBlank()) {
            Intent(Intent.ACTION_MAIN)
        } else {
            val normalizedUrl = if (
                requestedUrl.startsWith("https://", ignoreCase = true) ||
                requestedUrl.startsWith("http://", ignoreCase = true)
            ) requestedUrl else "https://" + requestedUrl
            Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl))
        }.apply {
            setClassName("com.watchlearn.ai", "com.watchlearn.ai.InAppBrowserActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            startActivity(intent)
            true to "Opened WatchLearn AI Browser"
        }.getOrElse {
            false to "The personal in-app browser is not included in this build."
        }
    }

    private fun openUrl(action: DeviceAction): Pair<Boolean, String> {
        val requestedUrl = action.url.ifBlank { action.text }.trim()
        if (requestedUrl.isBlank()) return false to "Open-url action is missing a URL."
        val normalizedUrl = if (
            requestedUrl.startsWith("https://", ignoreCase = true) ||
            requestedUrl.startsWith("http://", ignoreCase = true)
        ) {
            requestedUrl
        } else {
            "https://$requestedUrl"
        }
        val targetPackage = if (action.packageName.isBlank()) {
            null
        } else {
            resolvePackage(action.packageName)
                ?: return false to "Could not find an installed app named “${action.packageName}”."
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (targetPackage != null) setPackage(targetPackage)
        }
        if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return false to "No installed app can open $normalizedUrl${targetPackage?.let { " in $it" } ?: ""}."
        }
        startActivity(intent)
        return true to "Opened $normalizedUrl${targetPackage?.let { " in $it" } ?: ""}"
    }

    private fun tapText(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to "Tap-text action is missing text."
        val root = rootInActiveWindow ?: return false to "No active window is available."
        val node = root.findAccessibilityNodeInfosByText(text).firstOrNull()
            ?: allNodes(root).firstOrNull { node ->
                node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
            }
            ?: return false to "Could not find visible text “$text”."
        return clickNode(node)
    }

    private fun tapContentDescription(description: String): Pair<Boolean, String> {
        if (description.isBlank()) return false to "Tap-content-description action is missing text."
        val root = rootInActiveWindow ?: return false to "No active window is available."
        val node = allNodes(root).firstOrNull {
            it.contentDescription?.toString()?.contains(description, ignoreCase = true) == true
        } ?: return false to "Could not find a visible control described as “$description”."
        return clickNode(node)
    }

    private fun tapViewId(viewId: String): Pair<Boolean, String> {
        if (viewId.isBlank()) return false to "Tap-view-id action is missing view_id."
        val node = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
            ?: return false to "Could not find view ID “$viewId”."
        return clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Pair<Boolean, String> {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true to "Tapped the requested control."
            }
            current = current.parent
        }
        return false to "The requested control was found but could not be clicked."
    }

    private fun typeText(action: DeviceAction): Pair<Boolean, String> {
        if (action.text.isBlank()) return false to "Type-text action is missing text."
        val root = rootInActiveWindow ?: return false to "No active window is available for typing."
        val node = findTextField(root, action)
            ?: return false to "Could not find a visible text field."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) to
            "Entered text in the selected field."
    }

    private fun clearText(action: DeviceAction): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return false to "No active window is available for clearing text."
        val node = findTextField(root, action)
            ?: return false to "Could not find a visible text field to clear."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) to
            "Cleared the selected field."
    }

    private fun findTextField(
        root: AccessibilityNodeInfo,
        action: DeviceAction
    ): AccessibilityNodeInfo? {
        if (action.viewId.isNotBlank()) {
            root.findAccessibilityNodeInfosByViewId(action.viewId)
                .firstOrNull { it.isEditable }
                ?.let { return it }
        }

        val nodes = allNodes(root)
        if (action.label.isNotBlank()) {
            nodes.firstOrNull { node ->
                node.isEditable && listOf(
                    node.text,
                    node.hintText,
                    node.contentDescription
                ).any { value ->
                    value?.toString()?.contains(action.label, ignoreCase = true) == true
                }
            }?.let { return it }
        }

        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?.let { return it }
        return nodes.firstOrNull { it.isEditable }
    }

    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        return result
    }

    private fun dispatchTap(x: Float, y: Float): Pair<Boolean, String> {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        return dispatchGesture(gesture, null, null) to "Tapped the screen."
    }

    private fun dispatchSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Pair<Boolean, String> {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(100L, 10_000L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null) to "Swiped the screen."
    }

    private fun resolvePackage(identifier: String): String? {
        val value = identifier.trim()
        if (value.isBlank()) return null
        val launchableApps = queryLaunchableApps()
        if (value.contains('.') && launchableApps.any { it.packageName.equals(value, ignoreCase = true) }) {
            return value
        }
        val known = mapOf(
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "chrome browser" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "google maps" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "google play" to "com.android.vending",
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "watchlearn browser" to "com.watchlearn.ai",
            "watchlearn ai browser" to "com.watchlearn.ai"
        )
        known[value.lowercase(Locale.US)]?.let {
            if (launchableApps.any { app -> app.packageName == it }) return it
        }
        val exactMatch = launchableApps.firstOrNull { app ->
            app.label.equals(value, ignoreCase = true)
        }
        val partialMatch = launchableApps.firstOrNull { app ->
            app.label.contains(value, ignoreCase = true)
        }
        return (exactMatch ?: partialMatch)?.packageName
    }

    private data class LaunchableApp(val packageName: String, val label: String)

    private fun queryLaunchableApps(): List<LaunchableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_ALL
        ).mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(packageManager)?.toString()
                ?: activityInfo.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() }
                ?: activityInfo.packageName
            LaunchableApp(activityInfo.packageName, label)
        }.distinctBy { it.packageName }
    }

    private fun launchIntentForPackage(packageName: String): Intent? {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val launcherActivity = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_ALL
        ).firstOrNull()?.activityInfo
        return if (launcherActivity != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(launcherActivity.packageName, launcherActivity.name)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
    }

    companion object {
        @Volatile
        private var instance: WatchLearnAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        fun executeApproved(
            actions: List<DeviceAction>,
            callback: (Boolean, String) -> Unit
        ): Boolean {
            val service = instance ?: return false
            service.handler.post { service.execute(actions, callback) }
            return true
        }

        fun pauseApproved(): Boolean = instance?.pauseTask() == true

        fun resumeApproved(): Boolean = instance?.resumeTask() == true

        fun appendApproved(actions: List<DeviceAction>): Boolean =
            instance?.appendTask(actions) == true

        fun stopApproved(): Boolean = instance?.stopTask() == true

        fun isRunning(): Boolean = instance?.running == true

        fun isPaused(): Boolean = instance?.paused == true

        fun currentStatus(): String =
            instance?.actionStatus ?: "Accessibility service is not enabled."

        fun lastFailureReason(): String = instance?.failureReason.orEmpty()

        fun hasSavedTask(): Boolean = instance?.lastApprovedActions?.isNotEmpty() == true

        fun retryApproved(callback: (Boolean, String) -> Unit): Boolean {
            val service = instance ?: return false
            service.handler.post {
                if (service.running) {
                    callback(false, "A task is already running.")
                } else if (service.lastApprovedActions.isEmpty()) {
                    callback(false, "There is no saved phone task to retry.")
                } else {
                    service.execute(service.lastApprovedActions, callback)
                }
            }
            return true
        }

        fun isEnabled(service: android.content.Context): Boolean {
            val expected = "${service.packageName}/${WatchLearnAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                service.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
