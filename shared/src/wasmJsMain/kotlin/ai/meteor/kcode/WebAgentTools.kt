@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package ai.meteor.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerScreenshot
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebConsoleEntry
import ai.meteor.kcode.webcontainer.WebConsoleSnapshot
import ai.meteor.kcode.webcontainer.WebDebugScript
import ai.meteor.kcode.webcontainer.WebInteractionAction
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionResult
import ai.meteor.kcode.webcontainer.WebPageInspection
import ai.meteor.kcode.webcontainer.decodeWebInspection
import ai.meteor.kcode.webcontainer.decodeWebInteractionTarget
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult
import ai.meteor.kcode.webcontainer.WebPreviewSource
import ai.meteor.kcode.webcontainer.WebVirtualPath
import ai.meteor.kcode.tools.search.WebSearchConfiguration
import ai.meteor.kcode.tools.search.WebSearchProvider
import ai.meteor.kcode.tools.search.WebSearchTool
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.tools.io.normalizeWorkspacePath
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.js.toJsString

internal class WebToolPermissionState(
    var mode: ToolPermissionMode = ToolPermissionMode.Ask,
)

internal fun createWebKoogChatService(
    settingsStore: AppSettingsStore,
    permissionState: WebToolPermissionState,
): KoogChatService = createWebKoogChatRuntime(settingsStore, permissionState).chatService

internal fun createWebKoogChatRuntime(
    settingsStore: AppSettingsStore,
    permissionState: WebToolPermissionState,
): KcodeAgentRuntime {
    val workspace = WebAgentWorkspace()
    val webContainerController = BrowserWebContainerLauncher(workspace)
    return KcodeAgentRuntime(
        chatService = KoogChatService(
            additionalTools = ToolRegistry {
                tool(AgentReadFileTool(workspace))
                tool(AgentListDirectoryTool(workspace))
                tool(AgentWriteFileTool(workspace))
                tool(AgentEditFileTool(workspace))
                webContainerTools(webContainerController)
                tool(WebSearchTool(configurationProvider = {
                    settingsStore.load().let {
                        WebSearchConfiguration(
                            provider = WebSearchProvider.fromCode(it.webSearchProvider),
                            brightDataApiKey = it.webSearchApiKey,
                            exaApiKey = it.exaSearchApiKey,
                        )
                    }
                }))
            },
            toolPermissionModeProvider = { permissionState.mode },
            toolCallApprover = ToolCallApprover { request ->
                window.confirm(
                    buildString {
                        append("Allow ").append(request.name).append("?\n\n")
                        append(request.description.ifBlank { request.name }.take(2_048))
                        append("\n\nInput\n").append(request.input.take(8_192))
                    },
                )
            },
        ),
        webContainerController = webContainerController,
    )
}

internal class WebAgentWorkspace : AgentWorkspace {
    override suspend fun readText(path: String): String = readTextOrNull(path)
        ?: error("File does not exist: $path")

    override suspend fun writeText(path: String, content: String) {
        val relative = normalizeWorkspacePath(path, allowRoot = false)
        val size = content.encodeToByteArray().size
        require(size <= MAX_FILE_BYTES) { "File exceeds the $MAX_FILE_BYTES-byte limit" }
        val previous = localStorage.getItem(FILE_PREFIX + relative).orEmpty().encodeToByteArray().size
        require(totalBytes() - previous + size <= MAX_WORKSPACE_BYTES) { "Workspace size limit exceeded" }
        localStorage.setItem(FILE_PREFIX + relative, content)
    }

    override suspend fun list(path: String): List<AgentWorkspaceEntry> {
        val directory = normalizeWorkspacePath(path, allowRoot = true)
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val entries = linkedMapOf<String, AgentWorkspaceEntry>()
        workspaceFiles().forEach { (relative, content) ->
            if (!relative.startsWith(prefix)) return@forEach
            val remainder = relative.removePrefix(prefix)
            if (remainder.isEmpty()) return@forEach
            val child = remainder.substringBefore('/')
            val childRelative = prefix + child
            val directoryEntry = '/' in remainder
            entries[childRelative] = AgentWorkspaceEntry(
                path = "/workspace/$childRelative",
                directory = directoryEntry,
                size = if (directoryEntry) 0L else content.encodeToByteArray().size.toLong(),
            )
        }
        if (directory.isNotEmpty() && entries.isEmpty() && workspaceFiles().none { it.first.startsWith("$directory/") }) {
            error("Directory does not exist: $path")
        }
        return entries.values.sortedBy { it.path }
    }

    fun readTextOrNull(path: String): String? {
        val relative = normalizeWorkspacePath(path, allowRoot = false)
        return localStorage.getItem(FILE_PREFIX + relative)
    }

    private fun workspaceFiles(): List<Pair<String, String>> = buildList {
        for (index in 0 until localStorage.length) {
            val key = localStorage.key(index) ?: continue
            if (key.startsWith(FILE_PREFIX)) {
                add(key.removePrefix(FILE_PREFIX) to localStorage.getItem(key).orEmpty())
            }
        }
    }

    private fun totalBytes(): Int = workspaceFiles().sumOf { it.second.encodeToByteArray().size }

    private companion object {
        const val FILE_PREFIX = "kcode.workspace.file."
        const val MAX_FILE_BYTES = 1_048_576
        const val MAX_WORKSPACE_BYTES = 4_194_304
    }
}

internal class BrowserWebContainerLauncher(
    private val workspace: WebAgentWorkspace,
) : WebContainerController {
    private data class Session(
        var info: WebContainerInfo,
        val overlay: HTMLDivElement,
        val frame: HTMLIFrameElement,
        val screenshotToken: String?,
    )

    private var active: Session? = null

    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult {
        val containerId = "web-${Random.nextLong().toString(16)}"
        val source = request.source
        val localHtml: String?
        val remoteUrl: String?
        val entrySize: Long
        val screenshotToken: String?
        when (source) {
            is WebPreviewSource.WorkspaceFile -> {
                val html = workspace.readTextOrNull(source.location)
                    ?: error("Web entry does not exist: ${source.location}")
                screenshotToken = "capture-${Random.nextLong().toString(16)}"
                localHtml = installScreenshotResponder(inlineLocalAssets(html, source.location), screenshotToken)
                remoteUrl = null
                entrySize = html.encodeToByteArray().size.toLong()
            }
            is WebPreviewSource.RemoteWebsite -> {
                screenshotToken = null
                localHtml = null
                remoteUrl = source.location
                entrySize = 0L
            }
        }
        val preview = showPreview(
            title = request.title,
            html = localHtml,
            url = remoteUrl,
            onBackground = {
                active?.takeIf { it.info.id == containerId }?.let { session ->
                    session.overlay.style.visibility = "hidden"
                    session.overlay.style.setProperty("pointer-events", "none")
                    session.info = session.info.copy(state = WebContainerState.Background)
                }
            },
            onClose = { active = null },
        )
        active = Session(
            WebContainerInfo(
                containerId,
                request.entryPath,
                request.title,
                "web-sandboxed-iframe",
                WebContainerState.Foreground,
            ),
            preview.first,
            preview.second,
            screenshotToken,
        )
        return WebPreviewResult(
            containerId = containerId,
            entryPath = request.entryPath,
            entrySize = entrySize,
            presentation = "web-sandboxed-iframe",
        )
    }

    override suspend fun list(): List<WebContainerInfo> = active?.let { listOf(it.info) }.orEmpty()

    override suspend fun screenshot(containerId: String): WebContainerScreenshot {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("Web container is not running: $containerId")
        require(session.screenshotToken != null) {
            "Remote websites cannot be captured by the browser build because cross-origin iframe access is restricted"
        }
        val width = session.frame.clientWidth.coerceAtLeast(1)
        val height = session.frame.clientHeight.coerceAtLeast(1)
        val serializedDocument = requestSerializedDocument(session)
        val bytes = renderDocumentToPng(serializedDocument, width, height)
        return WebContainerScreenshot(containerId, bytes, width, height)
    }

    override suspend fun inspect(containerId: String): WebPageInspection {
        val session = requireSession(containerId)
        require(session.screenshotToken != null) {
            "Remote websites cannot be inspected by the browser build because cross-origin iframe access is restricted"
        }
        return decodeWebInspection(containerId, requestDebugScript(session, WebDebugScript.inspect))
    }

    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult {
        val session = requireSession(request.containerId)
        if (request.action == WebInteractionAction.Reload && session.screenshotToken == null) {
            session.frame.src = session.frame.src
            return WebInteractionResult(request.containerId, request.action, "page")
        }
        require(session.screenshotToken != null) {
            "Remote websites cannot be controlled by the browser build because cross-origin iframe access is restricted"
        }
        val target = decodeWebInteractionTarget(requestDebugScript(session, WebDebugScript.interact(request)))
        return WebInteractionResult(request.containerId, request.action, target)
    }

    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot {
        val session = requireSession(containerId)
        require(session.screenshotToken != null) {
            "Remote website console output is unavailable in the browser build because cross-origin iframe access is restricted"
        }
        val encoded = requestDebugScript(
            session,
            "JSON.stringify((window.__kcodeDebugConsole||[]).filter(function(e){return e.sequence>${cursor.coerceAtLeast(0)}}).slice(0,${limit.coerceIn(1, 200)}))",
        )
        val entries = Json.parseToJsonElement(encoded).jsonArray.map { item ->
            val entry = item.jsonObject
            WebConsoleEntry(
                sequence = entry.getValue("sequence").jsonPrimitive.long,
                level = entry.getValue("level").jsonPrimitive.content,
                message = entry.getValue("message").jsonPrimitive.content,
                source = entry["source"]?.jsonPrimitive?.contentOrNull,
                line = entry["line"]?.jsonPrimitive?.intOrNull,
            )
        }
        return WebConsoleSnapshot(containerId, entries, entries.lastOrNull()?.sequence ?: cursor)
    }

    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("Web container is not running: $containerId")
        session.overlay.style.visibility = if (state == WebContainerState.Foreground) "visible" else "hidden"
        session.overlay.style.setProperty("pointer-events", if (state == WebContainerState.Foreground) "auto" else "none")
        session.info = session.info.copy(state = state)
        return session.info
    }

    override suspend fun close(containerId: String) {
        val session = active?.takeIf { it.info.id == containerId }
            ?: error("Web container is not running: $containerId")
        session.overlay.remove()
        active = null
    }

    private fun requireSession(containerId: String): Session = active?.takeIf { it.info.id == containerId }
        ?: error("Web container is not running: $containerId")

    private fun inlineLocalAssets(html: String, entryPath: String): String {
        val baseDirectory = entryPath.substringBeforeLast('/', "/workspace")
        var rendered = SCRIPT_PATTERN.replace(html) { match ->
            val path = resolveAsset(baseDirectory, match.groupValues[2]) ?: return@replace match.value
            val script = workspace.readTextOrNull(path) ?: return@replace match.value
            "<script${match.groupValues[1]}${match.groupValues[3]}>${script.replace("</script", "<\\/script")}</script>"
        }
        rendered = STYLESHEET_PATTERN.replace(rendered) { match ->
            val path = resolveAsset(baseDirectory, match.groupValues[1]) ?: return@replace match.value
            val css = workspace.readTextOrNull(path) ?: return@replace match.value
            "<style>${inlineCssAssets(css, path.substringBeforeLast('/', "/workspace"))}</style>"
        }
        rendered = SOURCE_PATTERN.replace(rendered) { match ->
            val path = resolveAsset(baseDirectory, match.groupValues[2]) ?: return@replace match.value
            val content = workspace.readTextOrNull(path) ?: return@replace match.value
            "${match.groupValues[1]}${dataUrl(path, content)}${match.groupValues[3]}"
        }
        return rendered
    }

    private fun inlineCssAssets(css: String, baseDirectory: String): String = CSS_URL_PATTERN.replace(css) { match ->
        val raw = match.groupValues[2]
        val path = resolveAsset(baseDirectory, raw) ?: return@replace match.value
        val content = workspace.readTextOrNull(path) ?: return@replace match.value
        "url(${match.groupValues[1]}${dataUrl(path, content)}${match.groupValues[1]})"
    }

    private fun resolveAsset(baseDirectory: String, reference: String): String? {
        if (reference.startsWith("data:") || reference.startsWith("http:") ||
            reference.startsWith("https:") || reference.startsWith("//") || reference.startsWith("#")
        ) return null
        val clean = reference.substringBefore('?').substringBefore('#')
        val combined = if (clean.startsWith("/workspace/")) clean else "$baseDirectory/$clean"
        val segments = mutableListOf<String>()
        combined.removePrefix("/workspace/").split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex) else return null
                else -> segments += segment
            }
        }
        return "/workspace/${segments.joinToString("/")}"
    }

    private fun dataUrl(path: String, content: String): String {
        val mime = when (path.substringAfterLast('.', "").lowercase()) {
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            else -> "text/plain"
        }
        return "data:$mime;base64,${Base64.Default.encode(content.encodeToByteArray())}"
    }

    private fun showPreview(
        title: String,
        html: String?,
        url: String?,
        onBackground: () -> Unit,
        onClose: () -> Unit,
    ): Pair<HTMLDivElement, HTMLIFrameElement> {
        document.getElementById(PREVIEW_ID)?.remove()
        val overlay = document.createElement("div") as HTMLDivElement
        overlay.id = PREVIEW_ID
        overlay.style.position = "fixed"
        overlay.style.top = "0"
        overlay.style.right = "0"
        overlay.style.bottom = "0"
        overlay.style.left = "0"
        overlay.style.zIndex = "2147483647"
        overlay.style.background = "white"
        overlay.style.display = "flex"
        overlay.style.flexDirection = "column"

        val bar = document.createElement("div") as HTMLDivElement
        bar.textContent = title.take(80).ifBlank { "Web Container" }
        bar.style.height = "52px"
        bar.style.display = "flex"
        bar.style.alignItems = "center"
        bar.style.justifyContent = "space-between"
        bar.style.padding = "0 16px"
        bar.style.font = "600 15px system-ui, sans-serif"
        bar.style.borderBottom = "1px solid #e7e7e4"

        val actions = document.createElement("div") as HTMLDivElement
        actions.style.display = "flex"
        actions.style.setProperty("gap", "8px")
        val background = document.createElement("button") as HTMLButtonElement
        background.textContent = "Background"
        background.style.border = "1px solid #d7d9d5"
        background.style.borderRadius = "999px"
        background.style.padding = "8px 14px"
        background.style.background = "white"
        background.style.color = "#202622"
        background.onclick = { onBackground(); null }
        actions.appendChild(background)

        val close = document.createElement("button") as HTMLButtonElement
        close.textContent = "Close"
        close.style.border = "0"
        close.style.borderRadius = "999px"
        close.style.padding = "8px 14px"
        close.style.background = "#202622"
        close.style.color = "white"
        close.onclick = { overlay.remove(); onClose(); null }
        actions.appendChild(close)
        bar.appendChild(actions)

        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.setAttribute(
            "sandbox",
            if (url == null) "allow-scripts allow-forms allow-modals allow-downloads" else
                "allow-scripts allow-same-origin allow-forms allow-modals allow-downloads allow-popups",
        )
        frame.setAttribute("allow", "camera; microphone; geolocation; accelerometer; gyroscope; magnetometer")
        frame.style.border = "0"
        frame.style.width = "100%"
        frame.style.flex = "1"
        if (url == null) frame.srcdoc = requireNotNull(html) else frame.src = url
        overlay.appendChild(bar)
        overlay.appendChild(frame)
        requireNotNull(document.body).appendChild(overlay)
        return overlay to frame
    }

    private fun installScreenshotResponder(html: String, token: String): String {
        val script = """
            <script>(function(){
              var sequence=0,entries=[];window.__kcodeDebugConsole=entries;
              function text(value){try{return typeof value==='string'?value:JSON.stringify(value);}catch(_){return String(value);}}
              function record(level,args,source,line){entries.push({sequence:++sequence,level:level,message:Array.from(args).map(text).join(' '),source:source||null,line:line||null});if(entries.length>500)entries.shift();}
              ['log','info','warn','error','debug'].forEach(function(level){var original=console[level];console[level]=function(){record(level,arguments);return original.apply(console,arguments);};});
              addEventListener('error',function(e){record('error',[e.message],e.filename,e.lineno);});
              addEventListener('unhandledrejection',function(e){record('error',['Unhandled promise rejection',e.reason]);});
              window.addEventListener('message',function(event){
                if(event.data===${jsString("kcode-screenshot:$token")}){
                  try{var xml=new XMLSerializer().serializeToString(document.documentElement);parent.postMessage(${jsString("kcode-screenshot-result:$token:")}+xml,'*');}
                  catch(error){parent.postMessage(${jsString("kcode-screenshot-error:$token:")}+String(error),'*');}return;
                }
                if(typeof event.data!=='string'||event.data.indexOf(${jsString("kcode-debug-request:$token:")})!==0)return;
                try{var request=JSON.parse(event.data.slice(${"kcode-debug-request:$token:".length}));var value=(0,eval)(request.script);
                  parent.postMessage(${jsString("kcode-debug-result:$token:")}+request.id+':'+JSON.stringify({value:value}),'*');}
                catch(error){parent.postMessage(${jsString("kcode-debug-result:$token:")}+request.id+':'+JSON.stringify({error:String(error&&error.message||error)}),'*');}
              });
            })();</script>
        """.trimIndent()
        val bodyIndex = html.lastIndexOf("</body>", ignoreCase = true)
        return if (bodyIndex >= 0) html.substring(0, bodyIndex) + script + html.substring(bodyIndex) else html + script
    }

    private suspend fun requestDebugScript(session: Session, script: String): String = suspendCancellableCoroutine { continuation ->
        val requestId = Random.nextLong().toString(16)
        val responsePrefix = "kcode-debug-result:${session.screenshotToken}:$requestId:"
        val requestPayload = buildJsonObject {
            put("id", requestId)
            put("script", script)
        }.toString()
        lateinit var listener: (Event) -> Unit
        var retryHandle = 0
        var timeoutHandle = 0
        fun cleanup() {
            window.removeEventListener("message", listener)
            window.clearInterval(retryHandle)
            window.clearTimeout(timeoutHandle)
        }
        listener = { event ->
            val message = (event as? MessageEvent)?.data?.toString().orEmpty()
            if (message.startsWith(responsePrefix)) {
                cleanup()
                val response = Json.parseToJsonElement(message.removePrefix(responsePrefix)).jsonObject
                val error = response["error"]?.jsonPrimitive?.contentOrNull
                if (!continuation.isActive) Unit
                else if (error != null) continuation.resumeWithException(IllegalStateException(error))
                else continuation.resume(response.getValue("value").jsonPrimitive.content)
            }
        }
        window.addEventListener("message", listener)
        val send: () -> JsAny? = {
            session.frame.contentWindow?.postMessage(
                "kcode-debug-request:${session.screenshotToken}:$requestPayload".toJsString(),
                "*",
            )
            null
        }
        retryHandle = window.setInterval(send, 25)
        timeoutHandle = window.setTimeout({
            cleanup()
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Timed out waiting for Web debug response"))
            null
        }, 2_000)
        continuation.invokeOnCancellation { cleanup() }
        send()
    }

    private suspend fun requestSerializedDocument(session: Session): String = suspendCancellableCoroutine { continuation ->
        val successPrefix = "kcode-screenshot-result:${session.screenshotToken}:"
        val errorPrefix = "kcode-screenshot-error:${session.screenshotToken}:"
        lateinit var listener: (Event) -> Unit
        var retryHandle = 0
        var timeoutHandle = 0
        fun cleanup() {
            window.removeEventListener("message", listener)
            window.clearInterval(retryHandle)
            window.clearTimeout(timeoutHandle)
        }
        listener = { event ->
            val message = (event as? MessageEvent)?.data?.toString().orEmpty()
            when {
                message.startsWith(successPrefix) -> {
                    cleanup()
                    if (continuation.isActive) continuation.resume(message.removePrefix(successPrefix))
                }
                message.startsWith(errorPrefix) -> {
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message.removePrefix(errorPrefix)))
                }
            }
        }
        window.addEventListener("message", listener)
        val request: () -> JsAny? = {
            session.frame.contentWindow?.postMessage("kcode-screenshot:${session.screenshotToken}".toJsString(), "*")
            null
        }
        retryHandle = window.setInterval(request, 25)
        timeoutHandle = window.setTimeout({
            cleanup()
            if (continuation.isActive) continuation.resumeWithException(
                IllegalStateException("Timed out waiting for the Web container to become ready"),
            )
            null
        }, 1_500)
        continuation.invokeOnCancellation { cleanup() }
        request()
    }

    private suspend fun renderDocumentToPng(xml: String, width: Int, height: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height"><foreignObject width="100%" height="100%">$xml</foreignObject></svg>"""
            val image = document.createElement("img") as HTMLImageElement
            image.onload = {
                runCatching {
                    val canvas = document.createElement("canvas") as HTMLCanvasElement
                    canvas.width = width
                    canvas.height = height
                    val context = canvas.getContext("2d") as CanvasRenderingContext2D
                    context.drawImage(image, 0.0, 0.0)
                    val encoded = canvas.toDataURL("image/png").substringAfter(',')
                    Base64.Default.decode(encoded)
                }.fold(
                    onSuccess = { if (continuation.isActive) continuation.resume(it) },
                    onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                )
                null
            }
            image.onerror = { _, _, _, _, _ ->
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Could not render Web screenshot"))
                null
            }
            image.src = "data:image/svg+xml;base64,${Base64.Default.encode(svg.encodeToByteArray())}"
        }

    private fun jsString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private companion object {
        const val PREVIEW_ID = "kcode-web-preview"
        val SCRIPT_PATTERN = Regex(
            """<script([^>]*?)\s+src\s*=\s*["']([^"']+)["']([^>]*)>\s*</script>""",
            RegexOption.IGNORE_CASE,
        )
        val STYLESHEET_PATTERN = Regex(
            """<link(?=[^>]*\brel\s*=\s*["']stylesheet["'])[^>]*?href\s*=\s*["']([^"']+)["'][^>]*?>""",
            RegexOption.IGNORE_CASE,
        )
        val SOURCE_PATTERN = Regex("""(\bsrc\s*=\s*["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)
        val CSS_URL_PATTERN = Regex("""url\(\s*(["']?)([^"')]+)\1\s*\)""", RegexOption.IGNORE_CASE)
    }
}
