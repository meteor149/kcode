package ai.meteor.kcode.plugin.webcontainer.native

import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewSource
import ai.meteor.kcode.plugin.api.AndroidPluginWindowContent
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.webcontainer.R
import android.view.ContextThemeWrapper
import android.window.OnBackInvokedCallback

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json

internal class AndroidWebContainerContent(
    internal val activity: Activity,
    resources: AndroidWebPluginResources,
    private val sessions: AndroidWebContainerSessions,
    private val request: WebPreviewRequest,
    private val id: String,
) : ContextThemeWrapper(resources.forActivity(activity), R.style.Theme_Kcode_WebContainer), AndroidPluginWindowContent {
    internal lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var capabilityBridge: AndroidWebCapabilityBridge
    private lateinit var workspaceHandler: WorkspacePathHandler
    private var containerId: String? = null
    private var remoteWebsite = false
    internal var resourcesClosed = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.isNavigationBarContrastEnforced = false
        buildContent()
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        loadPreview()
    }

    override fun onResume() {
        if (!resourcesClosed) containerId?.let { sessions.setState(it, WebContainerState.Foreground) }
    }

    override fun onPause() {
        containerId?.let { id -> runCatching { sessions.setState(id, WebContainerState.Background) } }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun buildContent() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        toolbar.addView(floatingCloseButton())

        val navigationPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(5), dp(5))
            background = floatingSurface(radiusDp = 27)
            elevation = dp(8).toFloat()
            clipToOutline = true
        }

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        titleView = TextView(this).apply {
            setTextColor(Color.rgb(32, 38, 34))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        pathView = TextView(this).apply {
            setTextColor(Color.rgb(114, 117, 112))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        identity.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        identity.addView(pathView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        navigationPill.addView(identity, LinearLayout.LayoutParams(0, dp(44), 1f))

        val live = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(5), 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.icon_live)
                imageTintList = ColorStateList.valueOf(Color.rgb(62, 118, 83))
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(TextView(context).apply {
                text = getString(R.string.web_preview_live)
                setTextColor(Color.rgb(62, 118, 83))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(5), 0, 0, 0)
            })
        }
        navigationPill.addView(live, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        navigationPill.addView(
            toolbarButton(
                R.drawable.icon_background,
                getString(R.string.web_preview_background),
                ::movePreviewToBackground,
            ),
        )
        navigationPill.addView(toolbarButton(R.drawable.icon_reload, getString(R.string.web_preview_reload)) { webView.reload() })
        toolbar.addView(
            navigationPill,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(10) },
        )
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74)))

        workspaceHandler = WorkspacePathHandler(this)
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(WebWorkspace.PREVIEW_DOMAIN)
            .addPathHandler("/workspace/", workspaceHandler)
            .build()
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setGeolocationEnabled(true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    containerId?.let { id ->
                        sessions.recordConsole(
                            id,
                            consoleMessage.messageLevel().name.lowercase(),
                            consoleMessage.message(),
                            consoleMessage.sourceId(),
                            consoleMessage.lineNumber(),
                        )
                    }
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    capabilityBridge.handleWebPermissionRequest(request)
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback,
                ) {
                    capabilityBridge.handleGeolocationPermission(origin, callback)
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean = capabilityBridge.handleFileChooser(filePathCallback, fileChooserParams)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "https" && request.url.host == WebWorkspace.PREVIEW_DOMAIN) return false
                    if (remoteWebsite && (request.url.scheme == "https" || request.url.scheme == "http")) return false
                    if (request.url.scheme == "https" || request.url.scheme == "http") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!remoteWebsite) capabilityBridge.injectFallbackSdk()
                }
            }
        }
        capabilityBridge = AndroidWebCapabilityBridge(activity, webView, this).also { it.install() }
        WebView.setWebContentsDebuggingEnabled(
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        activity.setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun loadPreview() {
        check(!resourcesClosed) { "Web content is closed" }
        val source = request.source
        titleView.text = request.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.web_preview_default_title)
        pathView.text = source.location
        containerId = id
        when (source) {
            is WebPreviewSource.WorkspaceFile -> {
                val entry = runCatching { WebWorkspace.resolveEntry(this, source.location) }.getOrNull()
                if (entry == null) {
                    Toast.makeText(this, getString(R.string.web_preview_invalid), Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                remoteWebsite = false
                webView.loadUrl(WebWorkspace.previewUrl(WebWorkspace.relativePath(this, entry)))
            }
            is WebPreviewSource.RemoteWebsite -> {
                remoteWebsite = true
                webView.loadUrl(source.location)
            }
        }
    }

    private fun toolbarButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(drawableRes)
            imageTintList = ColorStateList.valueOf(Color.rgb(32, 38, 34))
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            val selectable = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
            setBackgroundResource(selectable.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun floatingCloseButton(): ImageButton =
        ImageButton(this).apply {
            setImageResource(R.drawable.icon_close)
            imageTintList = ColorStateList.valueOf(Color.rgb(32, 38, 34))
            contentDescription = getString(R.string.web_preview_close)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(15), dp(15), dp(15), dp(15))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(24, 32, 38, 34)),
                floatingSurface(26),
                null,
            )
            elevation = dp(8).toFloat()
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }

    private fun floatingSurface(radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.argb(245, 255, 255, 255))
            setStroke(dp(1), Color.argb(148, 228, 229, 226))
        }

    private fun movePreviewToBackground() {
        val id = containerId ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, getString(R.string.web_preview_background_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        sessions.setState(id, WebContainerState.Background)
        startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private val backCallback = OnBackInvokedCallback {
        if (webView.canGoBack()) webView.goBack() else activity.finish()
    }

    private fun finish() = activity.finish()

    override fun onDestroy() {
        sessions.detached(id)
    }

    override suspend fun close() {
        val failures = mutableListOf<Throwable>()
        runCatching { closeWebResources() }.exceptionOrNull()?.let(failures::add)
        runCatching { if (::capabilityBridge.isInitialized) capabilityBridge.awaitClosed() }.exceptionOrNull()?.let(failures::add)
        runCatching { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback) }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) throw PluginCleanupException("Android Web content", failures)
    }

    private fun closeWebResources() {
        if (resourcesClosed) return
        resourcesClosed = true
        val failures = mutableListOf<Throwable>()
        runCatching { if (::capabilityBridge.isInitialized) capabilityBridge.close() }.exceptionOrNull()?.let(failures::add)
        runCatching { if (::workspaceHandler.isInitialized) workspaceHandler.close() }.exceptionOrNull()?.let(failures::add)
        if (::webView.isInitialized) {
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }.exceptionOrNull()?.let(failures::add)
            runCatching { webView.webChromeClient = null; webView.webViewClient = WebViewClient() }.exceptionOrNull()?.let(failures::add)
            runCatching { webView.stopLoading() }.exceptionOrNull()?.let(failures::add)
            runCatching { webView.destroy() }.exceptionOrNull()?.let(failures::add)
        }
        if (failures.isNotEmpty()) throw PluginCleanupException("Android Web resources", failures)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean = ::capabilityBridge.isInitialized &&
        capabilityBridge.onRequestPermissionsResult(requestCode, permissions, grantResults)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        ::capabilityBridge.isInitialized && capabilityBridge.onActivityResult(requestCode, resultCode, data)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal suspend fun evaluateDebugScript(script: String): String = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { encoded ->
            if (!continuation.isActive) return@evaluateJavascript
            runCatching { Json.decodeFromString<String>(encoded) }
                .fold(continuation::resume) { continuation.cancel(IllegalStateException("Web debug script failed: $encoded", it)) }
        }
    }

    internal class WorkspacePathHandler(private val context: Context) : WebViewAssetLoader.PathHandler, AutoCloseable {
        private val lock = Any()
        private val streams = mutableSetOf<InputStream>()
        private var closed = false

        override fun handle(path: String): WebResourceResponse? {
            return synchronized(lock) {
                if (closed) return null
                val file: File = WebWorkspace.resolveAsset(context, path) ?: return null
                val mimeType = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: fallbackMimeType(file.extension)
                val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.contains("json")) "UTF-8" else null
                runCatching {
                    val stream = object : FilterInputStream(file.inputStream().buffered()) {
                        override fun close() {
                            synchronized(lock) {
                                try { super.close() } finally { streams.remove(this) }
                            }
                        }
                    }
                    streams += stream
                    WebResourceResponse(mimeType, encoding, stream)
                }.getOrNull()
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                val failures = mutableListOf<Throwable>()
                streams.toList().forEach { stream -> runCatching { stream.close() }.exceptionOrNull()?.let(failures::add) }
                streams.clear()
                if (failures.isNotEmpty()) throw PluginCleanupException("Web workspace responses", failures)
            }
        }

        private fun fallbackMimeType(extension: String): String = when (extension.lowercase()) {
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }

}
