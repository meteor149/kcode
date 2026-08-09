package ai.meteor.kcode.h5

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File

class H5ContainerActivity : Activity() {
    internal lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var capabilityBridge: AndroidH5CapabilityBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildContent()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        loadPreview(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadPreview(intent)
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
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.WHITE)
        }
        toolbar.addView(toolbarButton(R.drawable.icon_close, getString(R.string.h5_preview_close)) { finish() })

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
        toolbar.addView(identity, LinearLayout.LayoutParams(0, dp(48), 1f))

        val live = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(5), 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.icon_live)
                imageTintList = ColorStateList.valueOf(Color.rgb(62, 118, 83))
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(TextView(context).apply {
                text = getString(R.string.h5_preview_live)
                setTextColor(Color.rgb(62, 118, 83))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(5), 0, 0, 0)
            })
        }
        toolbar.addView(live, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        toolbar.addView(toolbarButton(R.drawable.icon_reload, getString(R.string.h5_preview_reload)) { webView.reload() })
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(228, 229, 226)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(H5Workspace.PREVIEW_DOMAIN)
            .addPathHandler("/workspace/", WorkspacePathHandler(this))
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
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "https" && request.url.host == H5Workspace.PREVIEW_DOMAIN) return false
                    if (request.url.scheme == "https" || request.url.scheme == "http") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    capabilityBridge.injectFallbackSdk()
                }
            }
        }
        capabilityBridge = AndroidH5CapabilityBridge(this, webView).also { it.install() }
        WebView.setWebContentsDebuggingEnabled(
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun loadPreview(intent: Intent) {
        val virtualPath = intent.getStringExtra(EXTRA_ENTRY_PATH).orEmpty()
        val entry = runCatching { H5Workspace.resolveEntry(this, virtualPath) }.getOrNull()
        if (entry == null) {
            Toast.makeText(this, R.string.h5_preview_invalid, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        titleView.text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.h5_preview_default_title)
        pathView.text = virtualPath
        webView.loadUrl(H5Workspace.previewUrl(H5Workspace.relativePath(this, entry)))
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

    override fun onDestroy() {
        if (::capabilityBridge.isInitialized) capabilityBridge.close()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (::capabilityBridge.isInitialized && capabilityBridge.onRequestPermissionsResult(requestCode, permissions, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @Deprecated("Legacy activity result is intentionally used by the isolated H5 container")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (::capabilityBridge.isInitialized && capabilityBridge.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal class WorkspacePathHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val file: File = H5Workspace.resolveAsset(context, path) ?: return null
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: fallbackMimeType(file.extension)
            val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.contains("json")) "UTF-8" else null
            return runCatching { WebResourceResponse(mimeType, encoding, file.inputStream().buffered()) }.getOrNull()
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

    companion object {
        const val EXTRA_ENTRY_PATH = "ai.meteor.kcode.h5.entry_path"
        const val EXTRA_TITLE = "ai.meteor.kcode.h5.title"
    }
}
