package com.watchlearn.ai

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.net.URLEncoder

class InAppBrowserActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var taskStatusText: TextView
    private lateinit var taskEditor: EditText
    private lateinit var taskEditorContainer: LinearLayout
    private lateinit var planPreview: TextView
    private lateinit var approvePlanButton: Button
    private lateinit var cancelPlanButton: Button
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPlan: DevicePlan? = null
    private var pendingPlanIsAppend = false
    private var bridgeMessage = ""
    private val statusHandler = Handler(Looper.getMainLooper())

    private val statusPoller = object : Runnable {
        override fun run() {
            updateTaskPanel()
            statusHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val browserTitle = TextView(this).apply {
            text = "WatchLearn AI Browser"
            textSize = 18f
            setPadding(8, 4, 8, 8)
        }
        root.addView(browserTitle, LinearLayout.LayoutParams(-1, -2))

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun navButton(label: String, action: () -> Unit): Button =
            Button(this).apply {
                text = label
                setOnClickListener { action() }
            }

        navigation.addView(navButton("‹") {
            if (webView.canGoBack()) webView.goBack()
        }, LinearLayout.LayoutParams(48, -2))
        navigation.addView(navButton("›") {
            if (webView.canGoForward()) webView.goForward()
        }, LinearLayout.LayoutParams(48, -2))
        navigation.addView(navButton("↻") {
            webView.reload()
        }, LinearLayout.LayoutParams(52, -2))
        navigation.addView(navButton("Home") {
            webView.loadUrl(DEFAULT_HOME_URL)
        }, LinearLayout.LayoutParams(76, -2))

        addressBar = EditText(this).apply {
            hint = "Search Google or enter a website link"
            setSingleLine(true)
            setSelectAllOnFocus(false)
            setOnEditorActionListener { _, _, _ ->
                navigateFromAddressBar()
                true
            }
        }
        navigation.addView(addressBar, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(navButton("Go") {
            navigateFromAddressBar()
        }, LinearLayout.LayoutParams(60, -2))
        root.addView(navigation)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, 4))

        webView = WebView(this)
        val browserFrame = FrameLayout(this)
        browserFrame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        browserFrame.addView(createTaskPanel(), taskPanelLayoutParams())
        root.addView(browserFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        BrowserTaskBridge.registerBrowserListeners(
            planListener = { plan, append ->
                runOnUiThread { showPlanForApproval(plan, append) }
            },
            messageListener = { message ->
                runOnUiThread {
                    bridgeMessage = message
                    updateTaskPanel()
                }
            }
        )

        configureWebView()

        val initialUrl = intent?.dataString
            ?: intent?.getStringExtra(EXTRA_URL)
            ?: DEFAULT_HOME_URL
        webView.loadUrl(normalizeUrl(initialUrl))
        statusHandler.post(statusPoller)
    }

    private fun taskPanelLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            setMargins(12, 0, 12, 12)
        }

    private fun createTaskPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            background = GradientDrawable().apply {
                setColor(Color.argb(225, 30, 30, 34))
                cornerRadius = 18f
            }
        }

        taskStatusText = TextView(this).apply {
            text = "AI task panel: no approved task is running."
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(taskStatusText, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun controlButton(label: String, action: () -> Unit): Button =
            Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { action() }
            }

        val pauseButton = controlButton("Pause") {
            if (!WatchLearnAccessibilityService.pauseApproved()) {
                showTaskToast("No running task to pause.")
            }
            updateTaskPanel()
        }
        val resumeButton = controlButton("Resume") {
            if (!WatchLearnAccessibilityService.resumeApproved()) {
                showTaskToast("No paused task to resume.")
            }
            updateTaskPanel()
        }
        val editButton = controlButton("Edit/Add") {
            taskEditorContainer.visibility =
                if (taskEditorContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (taskEditorContainer.visibility == View.VISIBLE) taskEditor.requestFocus()
        }
        val retryButton = controlButton("Retry") {
            val started = WatchLearnAccessibilityService.retryApproved { success, message ->
                if (!success) showTaskToast(message)
                bridgeMessage = message
                updateTaskPanel()
            }
            if (!started) showTaskToast("There is no saved phone task to retry.")
            updateTaskPanel()
        }

        listOf(pauseButton, resumeButton, editButton, retryButton).forEach { button ->
            controls.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(controls, LinearLayout.LayoutParams(-1, -2))

        taskEditorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 6, 0, 0)
        }
        taskEditor = EditText(this).apply {
            hint = "Edit the task or add a new step"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            minLines = 2
            maxLines = 3
            gravity = Gravity.TOP
        }
        taskEditorContainer.addView(taskEditor, LinearLayout.LayoutParams(-1, -2))

        val editorButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        editorButtons.addView(controlButton("Add step") {
            submitBrowserTask(append = true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        editorButtons.addView(controlButton("Run edited") {
            submitBrowserTask(append = false)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        taskEditorContainer.addView(editorButtons, LinearLayout.LayoutParams(-1, -2))

        planPreview = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(0, 6, 0, 0)
        }
        taskEditorContainer.addView(planPreview, LinearLayout.LayoutParams(-1, -2))

        val planButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        approvePlanButton = controlButton("Approve plan") {
            val plan = pendingPlan
            if (plan != null) {
                pendingPlan = null
                approvePlanButton.visibility = View.GONE
                cancelPlanButton.visibility = View.GONE
                planPreview.visibility = View.GONE
                BrowserTaskBridge.approvePlan(plan, pendingPlanIsAppend)
                bridgeMessage = "Sending the approved browser plan..."
                updateTaskPanel()
            }
        }
        cancelPlanButton = controlButton("Cancel") {
            pendingPlan = null
            approvePlanButton.visibility = View.GONE
            cancelPlanButton.visibility = View.GONE
            planPreview.visibility = View.GONE
            bridgeMessage = "Browser plan cancelled."
            updateTaskPanel()
        }
        planButtons.addView(approvePlanButton, LinearLayout.LayoutParams(0, -2, 1f))
        planButtons.addView(cancelPlanButton, LinearLayout.LayoutParams(0, -2, 1f))
        taskEditorContainer.addView(planButtons, LinearLayout.LayoutParams(-1, -2))
        approvePlanButton.visibility = View.GONE
        cancelPlanButton.visibility = View.GONE

        panel.addView(taskEditorContainer, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    private fun submitBrowserTask(append: Boolean) {
        val instruction = taskEditor.text.toString().trim()
        if (instruction.isBlank()) {
            showTaskToast("Enter an instruction first.")
            return
        }
        bridgeMessage = if (append) {
            "Sending the new step to the AI..."
        } else {
            "Sending the edited task to the AI..."
        }
        updateTaskPanel()
        BrowserTaskBridge.submitTask(instruction, append)
    }

    private fun showPlanForApproval(plan: DevicePlan, append: Boolean) {
        pendingPlan = plan
        pendingPlanIsAppend = append
        planPreview.text = formatDevicePlan(plan)
        planPreview.visibility = View.VISIBLE
        approvePlanButton.visibility = View.VISIBLE
        cancelPlanButton.visibility = View.VISIBLE
        taskEditorContainer.visibility = View.VISIBLE
        bridgeMessage = "Review the plan below, then approve it without leaving the browser."
        updateTaskPanel()
    }

    private fun updateTaskPanel() {
        val running = WatchLearnAccessibilityService.isRunning()
        val paused = WatchLearnAccessibilityService.isPaused()
        val status = if (running || bridgeMessage.isBlank()) {
            WatchLearnAccessibilityService.currentStatus()
        } else {
            bridgeMessage
        }
        taskStatusText.text = "AI task: " + status
        taskEditorContainer.let { container ->
            if (container.visibility == View.VISIBLE) {
                taskEditor.isEnabled = pendingPlan == null
            }
        }
        // The task buttons are the first four children in the compact row.
        val controls = (taskStatusText.parent as LinearLayout).getChildAt(1) as LinearLayout
        controls.getChildAt(0).isEnabled = running && !paused
        controls.getChildAt(1).isEnabled = running && paused
        controls.getChildAt(3).isEnabled = !running && WatchLearnAccessibilityService.hasSavedTask()
    }

    private fun showTaskToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " WatchLearnAI/0.5"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme.orEmpty().lowercase()
                if (scheme == "http" || scheme == "https") return false

                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }.getOrDefault(true)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url)
                addressBar.setSelection(addressBar.text.length)
            }

            override fun onPageFinished(view: WebView, url: String) {
                addressBar.setText(url)
                addressBar.setSelection(addressBar.text.length)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility =
                    if (newProgress in 1..99) ProgressBar.VISIBLE else ProgressBar.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) {
                    this@InAppBrowserActivity.title = title
                }
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return runCatching {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST)
                    true
                }.getOrElse {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType))
                    setDescription("Downloading from WatchLearn AI Browser")
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let {
                        addRequestHeader("Cookie", it)
                    }
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                    )
                }
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Download could not start", Toast.LENGTH_SHORT).show()
            }
        }

        webView.setOnLongClickListener { false }
    }

    private fun navigateFromAddressBar() {
        val entered = addressBar.text.toString().trim()
        if (entered.isBlank()) return
        webView.loadUrl(normalizeUrl(entered))
    }

    private fun normalizeUrl(value: String): String {
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value
        }
        if (value.contains(" ") || !value.contains(".")) {
            return DEFAULT_SEARCH_URL + URLEncoder.encode(value, Charsets.UTF_8.name())
        }
        return "https://$value"
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusPoller)
        BrowserTaskBridge.clearBrowserListeners()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "watchlearn.browser.url"
        const val EXTRA_OPEN_TASK_EDITOR = "watchlearn.browser.open_task_editor"
        const val EXTRA_TASK_EDITOR_HINT = "watchlearn.browser.task_editor_hint"
        private const val DEFAULT_HOME_URL = "https://www.google.com"
        private const val DEFAULT_SEARCH_URL = "https://www.google.com/search?q="
        private const val FILE_CHOOSER_REQUEST = 4107
    }

    @Deprecated("Activity result callback is retained for Android WebView file chooser compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST) return
        val results = if (resultCode == RESULT_OK && data?.data != null) {
            arrayOf(data.data!!)
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }
}
