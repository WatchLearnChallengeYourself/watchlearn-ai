package com.watchlearn.ai

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.net.URLEncoder

class InAppBrowserActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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
            webView.loadUrl("https://www.google.com")
        }, LinearLayout.LayoutParams(76, -2))

        addressBar = EditText(this).apply {
            hint = "Enter a website or search"
            singleLine = true
            setSelectAllOnFocus(false)
            setOnEditorActionListener { _, _, _ ->
                navigateFromAddressBar()
                true
            }
        }
        navigation.addView(
            addressBar,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
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
        root.addView(
            webView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        setContentView(root)

        configureWebView()

        val initialUrl = intent?.dataString
            ?: intent?.getStringExtra(EXTRA_URL)
            ?: "https://www.google.com"
        webView.loadUrl(normalizeUrl(initialUrl))
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
        settings.userAgentString = settings.userAgentString + " WatchLearnAI/0.4"

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

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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
            return "https://www.google.com/search?q=" +
                URLEncoder.encode(value, Charsets.UTF_8.name())
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
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "watchlearn.browser.url"
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
