package ai.meteor.kcode.h5

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
class IosH5ContainerLauncher(
    private val workspaceRoot: String,
    private val presentingViewController: () -> UIViewController?,
) : H5ContainerController {
    private data class Session(
        var info: H5ContainerInfo,
        val preview: IosH5PreviewViewController,
        val navigation: UINavigationController,
    )

    private var active: Session? = null

    constructor(
        workspaceRoot: String,
        presentingViewController: UIViewController,
    ) : this(workspaceRoot, { presentingViewController })

    override suspend fun launch(request: H5PreviewRequest): H5PreviewResult = withContext(Dispatchers.Main) {
        val relative = H5VirtualPath.relativeEntry(request.entryPath)
        val rootUrl = requireNotNull(
            NSURL.fileURLWithPath(workspaceRoot, isDirectory = true).URLByResolvingSymlinksInPath,
        )
        val entryUrl = requireNotNull(
            rootUrl.URLByAppendingPathComponent(relative)?.URLByResolvingSymlinksInPath,
        )
        val rootPath = requireNotNull(rootUrl.path).trimEnd('/') + "/"
        val entryPath = requireNotNull(entryUrl.path)
        require(entryPath.startsWith(rootPath)) { "H5 entry escapes /workspace" }
        require(NSFileManager.defaultManager.fileExistsAtPath(entryPath)) { "H5 entry does not exist: ${request.entryPath}" }

        active?.let { previous ->
            previous.preview.closeBridge()
            previous.navigation.dismissViewControllerAnimated(false, completion = null)
        }
        active = null
        val containerId = NSUUID().UUIDString
        val preview = IosH5PreviewViewController(
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
        requireNotNull(presentingViewController()) { "No iOS view controller is available for H5 preview" }
            .presentViewController(navigation, animated = true, completion = null)
        val size = NSFileManager.defaultManager.attributesOfItemAtPath(entryPath, error = null)
            ?.get(NSFileSize) as? NSNumber
        active = Session(
            H5ContainerInfo(
                containerId,
                request.entryPath,
                request.title,
                "ios-wkwebview",
                H5ContainerState.Foreground,
            ),
            preview,
            navigation,
        )
        H5PreviewResult(containerId, request.entryPath, size?.longLongValue ?: 0L, "ios-wkwebview")
    }

    override suspend fun list(): List<H5ContainerInfo> = withContext(Dispatchers.Main) {
        active?.let { listOf(it.info) }.orEmpty()
    }

    override suspend fun screenshot(containerId: String): H5ContainerScreenshot = withContext(Dispatchers.Main) {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("H5 container is not running: $containerId")
        session.preview.screenshot()
    }

    override suspend fun inspect(containerId: String): H5PageInspection = withContext(Dispatchers.Main) {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("H5 container is not running: $containerId")
        decodeH5Inspection(containerId, session.preview.evaluateDebugScript(H5DebugScript.inspect))
    }

    override suspend fun interact(request: H5InteractionRequest): H5InteractionResult = withContext(Dispatchers.Main) {
        val session = active?.takeIf { it.info.id == request.containerId }
            ?: error("H5 container is not running: ${request.containerId}")
        val target = when (request.action) {
            H5InteractionAction.Reload -> session.preview.reload().let { "page" }
            H5InteractionAction.Back -> session.preview.goBack().let { "history" }
            else -> decodeH5InteractionTarget(
                session.preview.evaluateDebugScript(H5DebugScript.interact(request)),
            )
        }
        H5InteractionResult(request.containerId, request.action, target)
    }

    override suspend fun console(containerId: String, cursor: Long, limit: Int): H5ConsoleSnapshot =
        withContext(Dispatchers.Main) {
            val session = active?.takeIf { it.info.id == containerId }
                ?: error("H5 container is not running: $containerId")
            decodeH5Console(
                containerId,
                session.preview.evaluateDebugScript(H5DebugScript.console(cursor, limit)),
                cursor,
            )
        }

    override suspend fun setState(containerId: String, state: H5ContainerState): H5ContainerInfo =
        withContext(Dispatchers.Main) {
            val session = active?.takeIf { it.info.id == containerId }
                ?: error("H5 container is not running: $containerId")
            if (session.info.state == state) return@withContext session.info
            when (state) {
                H5ContainerState.Foreground -> {
                    val presenter = requireNotNull(presentingViewController()) {
                        "No iOS view controller is available for H5 preview"
                    }
                    awaitPresentation(presenter, session.navigation)
                }
                H5ContainerState.Background -> {
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
            ?: error("H5 container is not running: $containerId")
        active = null
        session.preview.closeBridge()
        if (session.info.state == H5ContainerState.Foreground) {
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
        if (session.info.state == H5ContainerState.Background) return
        session.preview.backgroundTransition = true
        session.info = session.info.copy(state = H5ContainerState.Background)
        session.navigation.dismissViewControllerAnimated(true) {
            session.preview.backgroundTransition = false
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosH5PreviewViewController(
    private val titleText: String,
    private val entryUrl: NSURL,
    private val rootUrl: NSURL,
    private val containerId: String,
    private val onClosed: (String) -> Unit,
) : UIViewController(nibName = null, bundle = null) {
    private lateinit var webView: WKWebView
    private lateinit var bridge: IosH5CapabilityBridge
    var backgroundTransition: Boolean = false

    override fun viewDidLoad() {
        super.viewDidLoad()
        title = "$titleText  •  Live"
        val configuration = WKWebViewConfiguration()
        configuration.userContentController.addUserScript(
            WKUserScript(
                source = H5DebugScript.consoleCapture,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )
        webView = WKWebView(frame = view.bounds, configuration = configuration).apply {
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        }
        bridge = IosH5CapabilityBridge(this, webView)
        bridge.install(configuration.userContentController)
        view.addSubview(webView)
        webView.loadFileURL(entryUrl, allowingReadAccessToURL = rootUrl)
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (!backgroundTransition && (isBeingDismissed() || parentViewController?.isBeingDismissed() == true)) {
            closeBridge()
            onClosed(containerId)
        }
    }

    fun closeBridge() {
        if (this::bridge.isInitialized) bridge.close(webView.configuration.userContentController)
    }

    fun reload() {
        check(this::webView.isInitialized) { "H5 container is not ready: $containerId" }
        webView.reload()
    }

    fun goBack() {
        check(this::webView.isInitialized) { "H5 container is not ready: $containerId" }
        check(webView.canGoBack) { "H5 container has no previous page" }
        webView.goBack()
    }

    suspend fun evaluateDebugScript(script: String): String = suspendCancellableCoroutine { continuation ->
        check(this::webView.isInitialized) { "H5 container is not ready: $containerId" }
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
                continuation.resumeWithException(IllegalStateException("H5 debug operation returned no result"))
            } else {
                continuation.resume(encoded)
            }
        }
    }

    suspend fun screenshot(): H5ContainerScreenshot = suspendCancellableCoroutine { continuation ->
        check(this::webView.isInitialized) { "H5 container is not ready for a screenshot: $containerId" }
        webView.takeSnapshotWithConfiguration(null) { image, error ->
            if (!continuation.isActive) return@takeSnapshotWithConfiguration
            if (image == null) {
                continuation.resumeWithException(IllegalStateException(error?.localizedDescription ?: "Could not capture H5 screenshot"))
                return@takeSnapshotWithConfiguration
            }
            val data = UIImagePNGRepresentation(image)
            if (data == null) {
                continuation.resumeWithException(IllegalStateException("Could not encode H5 screenshot"))
                return@takeSnapshotWithConfiguration
            }
            val pointer = data.bytes?.reinterpret<ByteVar>()
                ?: return@takeSnapshotWithConfiguration continuation.resumeWithException(
                    IllegalStateException("H5 screenshot is empty"),
                )
            val bytes = ByteArray(data.length.toInt()) { index -> pointer[index.toLong()] }
            val bounds = webView.bounds.useContents { size.width.toInt() to size.height.toInt() }
            continuation.resume(
                H5ContainerScreenshot(
                    containerId,
                    bytes,
                    bounds.first,
                    bounds.second,
                ),
            )
        }
    }
}
