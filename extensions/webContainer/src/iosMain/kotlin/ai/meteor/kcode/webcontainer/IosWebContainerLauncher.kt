package ai.meteor.kcode.webcontainer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UINavigationController
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIAction
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.UIImagePNGRepresentation
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
class IosWebContainerLauncher(
    private val workspaceRoot: String,
    private val presentingViewController: () -> UIViewController?,
) : WebContainerController {
    private data class Session(
        var info: WebContainerInfo,
        val preview: IosWebPreviewViewController,
        val navigation: UINavigationController,
    )

    private var active: Session? = null

    constructor(
        workspaceRoot: String,
        presentingViewController: UIViewController,
    ) : this(workspaceRoot, { presentingViewController })

    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult = withContext(Dispatchers.Main) {
        val source = request.source
        val rootUrl: NSURL?
        val entryUrl: NSURL
        val entrySize: Long
        when (source) {
            is WebPreviewSource.WorkspaceFile -> {
                val relative = WebVirtualPath.relativeEntry(source.location)
                rootUrl = requireNotNull(
                    NSURL.fileURLWithPath(workspaceRoot, isDirectory = true).URLByResolvingSymlinksInPath,
                )
                entryUrl = requireNotNull(
                    rootUrl.URLByAppendingPathComponent(relative)?.URLByResolvingSymlinksInPath,
                )
                val rootPath = requireNotNull(rootUrl.path).trimEnd('/') + "/"
                val entryPath = requireNotNull(entryUrl.path)
                require(entryPath.startsWith(rootPath)) { "Web entry escapes /workspace" }
                require(NSFileManager.defaultManager.fileExistsAtPath(entryPath)) {
                    "Web entry does not exist: ${request.entryPath}"
                }
                val size = NSFileManager.defaultManager.attributesOfItemAtPath(entryPath, error = null)
                    ?.get(NSFileSize) as? NSNumber
                entrySize = size?.longLongValue ?: 0L
            }
            is WebPreviewSource.RemoteWebsite -> {
                rootUrl = null
                entryUrl = requireNotNull(NSURL.URLWithString(source.location)) { "Invalid remote website URL" }
                entrySize = 0L
            }
        }

        active?.let { previous ->
            previous.preview.closeBridge()
            previous.navigation.dismissViewControllerAnimated(false, completion = null)
        }
        active = null
        val containerId = NSUUID().UUIDString
        val preview = IosWebPreviewViewController(
            titleText = request.title,
            entryUrl = entryUrl,
            rootUrl = rootUrl,
            onClosed = { id -> if (active?.info?.id == id) active = null },
            containerId = containerId,
        )
        val navigation = UINavigationController(rootViewController = preview)
        navigation.navigationBar.topItem?.rightBarButtonItem = UIBarButtonItem(
            primaryAction = UIAction.actionWithTitle(
                title = "Background",
                image = null,
                identifier = null,
                handler = { movePreviewToBackground(containerId) },
            ),
        )
        requireNotNull(presentingViewController()) { "No iOS view controller is available for Web container" }
            .presentViewController(navigation, animated = true, completion = null)
        active = Session(
            WebContainerInfo(
                containerId,
                request.entryPath,
                request.title,
                "ios-wkwebview",
                WebContainerState.Foreground,
            ),
            preview,
            navigation,
        )
        WebPreviewResult(containerId, request.entryPath, entrySize, "ios-wkwebview")
    }

    override suspend fun list(): List<WebContainerInfo> = withContext(Dispatchers.Main) {
        active?.let { listOf(it.info) }.orEmpty()
    }

    override suspend fun screenshot(containerId: String): WebContainerScreenshot = withContext(Dispatchers.Main) {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("Web container is not running: $containerId")
        session.preview.screenshot()
    }

    override suspend fun inspect(containerId: String): WebPageInspection = withContext(Dispatchers.Main) {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("Web container is not running: $containerId")
        decodeWebInspection(containerId, session.preview.evaluateDebugScript(WebDebugScript.inspect))
    }

    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = withContext(Dispatchers.Main) {
        val session = active?.takeIf { it.info.id == request.containerId }
            ?: error("Web container is not running: ${request.containerId}")
        val target = when (request.action) {
            WebInteractionAction.Reload -> session.preview.reload().let { "page" }
            WebInteractionAction.Back -> session.preview.goBack().let { "history" }
            else -> decodeWebInteractionTarget(
                session.preview.evaluateDebugScript(WebDebugScript.interact(request)),
            )
        }
        WebInteractionResult(request.containerId, request.action, target)
    }

    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot =
        withContext(Dispatchers.Main) {
            val session = active?.takeIf { it.info.id == containerId }
                ?: error("Web container is not running: $containerId")
            decodeWebConsole(
                containerId,
                session.preview.evaluateDebugScript(WebDebugScript.console(cursor, limit)),
                cursor,
            )
        }

    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo =
        withContext(Dispatchers.Main) {
            val session = active?.takeIf { it.info.id == containerId }
                ?: error("Web container is not running: $containerId")
            if (session.info.state == state) return@withContext session.info
            when (state) {
                WebContainerState.Foreground -> {
                    val presenter = requireNotNull(presentingViewController()) {
                        "No iOS view controller is available for Web container"
                    }
                    awaitPresentation(presenter, session.navigation)
                }
                WebContainerState.Background -> {
                    session.preview.backgroundTransition = true
                    awaitDismissal(session.navigation)
                    session.preview.backgroundTransition = false
                }
            }
            session.info = session.info.copy(state = state)
            session.info
        }

    override suspend fun close(containerId: String) = withContext(Dispatchers.Main) {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("Web container is not running: $containerId")
        active = null
        session.preview.closeBridge()
        if (session.info.state == WebContainerState.Foreground) {
            session.navigation.dismissViewControllerAnimated(true, completion = null)
        }
    }

    private suspend fun awaitPresentation(presenter: UIViewController, controller: UIViewController) =
        suspendCancellableCoroutine { continuation ->
            presenter.presentViewController(controller, animated = true) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

    private suspend fun awaitDismissal(controller: UIViewController) =
        suspendCancellableCoroutine { continuation ->
            controller.dismissViewControllerAnimated(true) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

    private fun movePreviewToBackground(containerId: String) {
        val session = active?.takeIf { it.info.id == containerId } ?: return
        if (session.info.state == WebContainerState.Background) return
        session.preview.backgroundTransition = true
        session.info = session.info.copy(state = WebContainerState.Background)
        session.navigation.dismissViewControllerAnimated(true) {
            session.preview.backgroundTransition = false
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosWebPreviewViewController(
    private val titleText: String,
    private val entryUrl: NSURL,
    private val rootUrl: NSURL?,
    private val containerId: String,
    private val onClosed: (String) -> Unit,
) : UIViewController(nibName = null, bundle = null) {
    private lateinit var webView: WKWebView
    private var bridge: IosWebCapabilityBridge? = null
    var backgroundTransition: Boolean = false

    override fun viewDidLoad() {
        super.viewDidLoad()
        title = "$titleText  •  Live"
        val configuration = WKWebViewConfiguration()
        configuration.userContentController.addUserScript(
            WKUserScript(
                source = WebDebugScript.consoleCapture,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )
        webView = WKWebView(frame = view.bounds, configuration = configuration).apply {
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        }
        if (rootUrl != null) {
            bridge = IosWebCapabilityBridge(this, webView).also {
                it.install(configuration.userContentController)
            }
        }
        view.addSubview(webView)
        if (rootUrl != null) {
            webView.loadFileURL(entryUrl, allowingReadAccessToURL = rootUrl)
        } else {
            webView.loadRequest(NSURLRequest.requestWithURL(entryUrl))
        }
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (!backgroundTransition && (isBeingDismissed() || parentViewController?.isBeingDismissed() == true)) {
            closeBridge()
            onClosed(containerId)
        }
    }

    fun closeBridge() {
        bridge?.close(webView.configuration.userContentController)
        bridge = null
    }

    fun reload() {
        check(this::webView.isInitialized) { "Web container is not ready: $containerId" }
        webView.reload()
    }

    fun goBack() {
        check(this::webView.isInitialized) { "Web container is not ready: $containerId" }
        check(webView.canGoBack) { "Web container has no previous page" }
        webView.goBack()
    }

    suspend fun evaluateDebugScript(script: String): String = suspendCancellableCoroutine { continuation ->
        check(this::webView.isInitialized) { "Web container is not ready: $containerId" }
        webView.evaluateJavaScript(script) { result, error ->
            if (!continuation.isActive) return@evaluateJavaScript
            if (error != null) {
                continuation.resumeWithException(
                    IllegalStateException(error.localizedDescription),
                )
                return@evaluateJavaScript
            }
            val encoded = result as? String
            if (encoded == null) {
                continuation.resumeWithException(IllegalStateException("Web debug operation returned no result"))
            } else {
                continuation.resume(encoded)
            }
        }
    }

    suspend fun screenshot(): WebContainerScreenshot = suspendCancellableCoroutine { continuation ->
        check(this::webView.isInitialized) { "Web container is not ready for a screenshot: $containerId" }
        webView.takeSnapshotWithConfiguration(null) { image, error ->
            if (!continuation.isActive) return@takeSnapshotWithConfiguration
            if (image == null) {
                continuation.resumeWithException(IllegalStateException(error?.localizedDescription ?: "Could not capture Web screenshot"))
                return@takeSnapshotWithConfiguration
            }
            val data = UIImagePNGRepresentation(image)
            if (data == null) {
                continuation.resumeWithException(IllegalStateException("Could not encode Web screenshot"))
                return@takeSnapshotWithConfiguration
            }
            val pointer = data.bytes?.reinterpret<ByteVar>()
                ?: return@takeSnapshotWithConfiguration continuation.resumeWithException(
                    IllegalStateException("Web screenshot is empty"),
                )
            val bytes = ByteArray(data.length.toInt()) { index -> pointer[index.toLong()] }
            val bounds = webView.bounds.useContents { size.width.toInt() to size.height.toInt() }
            continuation.resume(
                WebContainerScreenshot(
                    containerId,
                    bytes,
                    bounds.first,
                    bounds.second,
                ),
            )
        }
    }
}
