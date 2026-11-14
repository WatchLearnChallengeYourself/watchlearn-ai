package com.watchlearn.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
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
    "tap_text" -> "Tap “${action.text}”"
    "tap_view_id" -> "Tap view ${action.viewId}"
    "tap" -> "Tap screen at (${action.x ?: 0f}, ${action.y ?: 0f})"
    "type_text" -> "Type “${action.text}”"
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
        executeAt(actions, 0, callback)
    }

    private fun executeAt(
        actions: List<DeviceAction>,
        index: Int,
        callback: (Boolean, String) -> Unit
    ) {
        if (index >= actions.size) {
            callback(true, "Approved phone actions completed.")
            return
        }
        val action = actions[index]
        val result = runAction(action)
        if (!result.first) {
            callback(false, result.second)
            return
        }
        val delay = when (action.type) {
            "launch_app", "open_app", "back", "home", "recents", "notifications" -> 1_000L
            "wait" -> action.durationMs.coerceIn(0L, 30_000L)
            else -> 350L
        }
        handler.postDelayed({ executeAt(actions, index + 1, callback) }, delay)
    }

    private fun runAction(action: DeviceAction): Pair<Boolean, String> {
        return when (action.type) {
            "launch_app", "open_app" -> {
                val packageName = resolvePackage(action.packageName.ifBlank { action.text })
                    ?: return false to "Could not find an installed app named “${action.packageName.ifBlank { action.text }}”."
                val launchIntent = launchIntentForPackage(packageName)
                    ?: return false to "The app is installed but has no launchable activity: $packageName"
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                true to "Opened $packageName"
            }
            "tap_text" -> tapText(action.text)
            "tap_view_id" -> tapViewId(action.viewId)
            "tap" -> {
                val x = action.x ?: return false to "Tap action is missing x."
                val y = action.y ?: return false to "Tap action is missing y."
                dispatchTap(x, y)
            }
            "type_text" -> typeText(action)
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
            else -> false to "Unsupported phone action: ${action.type}"
        }
    }

    private fun tapText(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to "Tap-text action is missing text."
        val node = rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
            ?: return false to "Could not find visible text “$text”."
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
        val node = if (action.viewId.isNotBlank()) {
            root.findAccessibilityNodeInfosByViewId(action.viewId).firstOrNull()
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } ?: return false to "No focused text field was found."
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) to
            "Entered text in the focused field."
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
            "tiktok" to "com.zhiliaoapp.musically"
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
