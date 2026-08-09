package ai.meteor.kcode.h5

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.UIKit.UINavigationController
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@OptIn(ExperimentalForeignApi::class)
class IosH5ContainerLauncher(
    private val workspaceRoot: String,
    private val presentingViewController: () -> UIViewController?,
) : H5ContainerLauncher {
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

        val preview = IosH5PreviewViewController(
            titleText = request.title,
            entryUrl = entryUrl,
            rootUrl = rootUrl,
        )
        val navigation = UINavigationController(rootViewController = preview)
        requireNotNull(presentingViewController()) { "No iOS view controller is available for H5 preview" }
            .presentViewController(navigation, animated = true, completion = null)
        val size = NSFileManager.defaultManager.attributesOfItemAtPath(entryPath, error = null)
            ?.get(NSFileSize) as? NSNumber
        H5PreviewResult(request.entryPath, size?.longLongValue ?: 0L, "ios-wkwebview")
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosH5PreviewViewController(
    private val titleText: String,
    private val entryUrl: NSURL,
    private val rootUrl: NSURL,
) : UIViewController(nibName = null, bundle = null) {
    private lateinit var webView: WKWebView
    private lateinit var bridge: IosH5CapabilityBridge

    override fun viewDidLoad() {
        super.viewDidLoad()
        title = "$titleText  •  Live"
        val configuration = WKWebViewConfiguration()
        webView = WKWebView(frame = view.bounds, configuration = configuration).apply {
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        }
        bridge = IosH5CapabilityBridge(this, webView, requireNotNull(rootUrl.path))
        bridge.install(configuration.userContentController)
        view.addSubview(webView)
        webView.loadFileURL(entryUrl, allowingReadAccessToURL = rootUrl)
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (this::bridge.isInitialized) bridge.close(webView.configuration.userContentController)
    }
}
