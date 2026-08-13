package ai.meteor.kcode.webcontainer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DesktopWebContainerLauncher(
    private val workspaceRoot: Path,
) : WebContainerController {
    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult = withContext(Dispatchers.IO) {
        when (val source = request.source) {
            is WebPreviewSource.WorkspaceFile -> {
                val root = Files.createDirectories(workspaceRoot).toRealPath(LinkOption.NOFOLLOW_LINKS)
                val relative = WebVirtualPath.relativeEntry(source.location)
                val entry = resolveExisting(root, relative) ?: error("Web entry does not exist: ${request.entryPath}")
                require(Files.isRegularFile(entry)) { "Web entry is not a file" }
                val session = DesktopPreviewSession.startLocal(root, relative, request.title)
                WebPreviewResult(session.id, request.entryPath, Files.size(entry), session.presentation)
            }
            is WebPreviewSource.RemoteWebsite -> {
                val session = DesktopPreviewSession.startRemote(URI(source.location), request.title)
                WebPreviewResult(session.id, source.location, 0L, session.presentation)
            }
        }
    }

    override suspend fun list(): List<WebContainerInfo> = DesktopPreviewSession.list()

    override suspend fun screenshot(containerId: String): WebContainerScreenshot =
        DesktopPreviewSession.screenshot(containerId)

    override suspend fun inspect(containerId: String): WebPageInspection = withContext(Dispatchers.IO) {
        DesktopPreviewSession.inspect(containerId)
    }

    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = withContext(Dispatchers.IO) {
        DesktopPreviewSession.interact(request)
    }

    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot =
        withContext(Dispatchers.IO) { DesktopPreviewSession.console(containerId, cursor, limit) }

    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo =
        withContext(Dispatchers.IO) { DesktopPreviewSession.setState(containerId, state) }

    override suspend fun close(containerId: String) {
        DesktopPreviewSession.close(containerId)
    }

}

private class DesktopPreviewSession private constructor(
    private val server: HttpServer?,
    private val browser: DesktopChromiumSession?,
    val id: String,
    private var info: WebContainerInfo,
    val previewUri: URI,
) {
    val presentation: String get() = info.presentation

    companion object {
        private var active: DesktopPreviewSession? = null

        @Synchronized
        fun startLocal(root: Path, entryPath: String, title: String): DesktopPreviewSession {
            active?.shutdown()
            val id = UUID.randomUUID().toString()
            val token = UUID.randomUUID().toString().replace("-", "")
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            val prefix = "/$token/"
            server.createContext(prefix) { exchange ->
                runCatching { handleRequest(exchange, root, prefix, entryPath, title, id) }
                    .onFailure { exchange.respond(500, "text/plain; charset=utf-8", "Preview error") }
            }
            server.executor = Executors.newCachedThreadPool { task ->
                Thread(task, "kcode-web-preview").apply { isDaemon = true }
            }
            server.start()
            val host = server.address.address.hostAddress.let { if (':' in it) "[$it]" else it }
            val previewUri = URI("http://$host:${server.address.port}${prefix}preview")
            val browser = DesktopChromiumSession.start(previewUri)
            if (browser == null) {
                require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    server.stop(0)
                    "The desktop environment cannot open a Web container window"
                }
                Desktop.getDesktop().browse(previewUri)
            }
            val presentation = if (browser == null) "desktop-external-browser" else "desktop-managed-chromium"
            val info = WebContainerInfo(id, "/workspace/$entryPath", title, presentation, WebContainerState.Foreground)
            return DesktopPreviewSession(server, browser, id, info, previewUri).also {
                active = it
            }
        }

        @Synchronized
        fun startRemote(url: URI, title: String): DesktopPreviewSession {
            require(url.scheme.equals("http", true) || url.scheme.equals("https", true)) { "Remote website must use HTTP(S)" }
            require(!url.host.isNullOrBlank()) { "Remote website URL must include a host" }
            active?.shutdown()
            val id = UUID.randomUUID().toString()
            val browser = DesktopChromiumSession.start(url)
            if (browser == null) {
                require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    "The desktop environment cannot open a remote website"
                }
                Desktop.getDesktop().browse(url)
            }
            val presentation = if (browser == null) "desktop-external-browser" else "desktop-managed-chromium"
            val info = WebContainerInfo(id, url.toString(), title, presentation, WebContainerState.Foreground)
            return DesktopPreviewSession(null, browser, id, info, url).also { active = it }
        }

        @Synchronized
        fun list(): List<WebContainerInfo> = active?.let { listOf(it.info) }.orEmpty()

        @Synchronized
        fun screenshot(id: String): WebContainerScreenshot {
            val session = requireActive(id)
            val bytes = session.browser?.captureScreenshot()
                ?: error("This Web container was opened by an unmanaged external browser and cannot be captured")
            val dimensions = pngDimensions(bytes)
            return WebContainerScreenshot(id, bytes, dimensions.first, dimensions.second)
        }

        @Synchronized
        fun inspect(id: String): WebPageInspection {
            val browser = requireActive(id).browser
                ?: error("This Web container was opened by an unmanaged external browser and cannot be inspected")
            return decodeWebInspection(id, browser.evaluate(WebDebugScript.inspect))
        }

        @Synchronized
        fun interact(request: WebInteractionRequest): WebInteractionResult {
            val browser = requireActive(request.containerId).browser
                ?: error("This Web container was opened by an unmanaged external browser and cannot be controlled")
            val target = when {
                request.action == WebInteractionAction.Click && request.handle == null && request.selector == null -> {
                    val x = requireNotNull(request.x) { "x is required for a coordinate click" }
                    val y = requireNotNull(request.y) { "y is required for a coordinate click" }
                    browser.click(x, y)
                    "point($x,$y)"
                }
                request.action == WebInteractionAction.Reload -> browser.reload().let { "page" }
                request.action == WebInteractionAction.Back -> browser.back().let { "history" }
                else -> decodeWebInteractionTarget(browser.evaluate(WebDebugScript.interact(request)))
            }
            return WebInteractionResult(request.containerId, request.action, target)
        }

        @Synchronized
        fun console(id: String, cursor: Long, limit: Int): WebConsoleSnapshot {
            val browser = requireActive(id).browser
                ?: error("This Web container was opened by an unmanaged external browser and has no console connection")
            return browser.console(id, cursor, limit)
        }

        @Synchronized
        fun setState(id: String, state: WebContainerState): WebContainerInfo {
            val session = requireActive(id)
            val browser = session.browser
                ?: error("This Web container was opened by an unmanaged external browser and cannot switch foreground state")
            browser.setState(state)
            session.info = session.info.copy(state = state)
            return session.info
        }

        @Synchronized
        fun close(id: String) {
            val session = requireActive(id)
            session.shutdown()
            active = null
        }

        private fun requireActive(id: String): DesktopPreviewSession =
            active?.takeIf { it.id == id } ?: error("Web container is not running: $id")

        private fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
            require(bytes.size >= 24 && bytes.copyOfRange(1, 4).decodeToString() == "PNG") { "Invalid PNG screenshot" }
            fun intAt(offset: Int): Int = (0..3).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 0xff) }
            return intAt(16) to intAt(20)
        }

        private fun handleRequest(
            exchange: HttpExchange,
            root: Path,
            prefix: String,
            entryPath: String,
            title: String,
            containerId: String,
        ) {
            val route = exchange.requestURI.rawPath.removePrefix(prefix)
            if (route == "background" && exchange.requestMethod == "POST") {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                CompletableFuture.runAsync {
                    runCatching { setState(containerId, WebContainerState.Background) }
                }
                return
            }
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.respond(405, "text/plain", "Method not allowed")
                return
            }
            if (route == "preview") {
                val entryUrl = prefix + "workspace/" + entryPath.split('/').joinToString("/") { encodePathPart(it) }
                val html = previewShell(title, entryPath, entryUrl)
                exchange.respond(200, "text/html; charset=utf-8", html)
                return
            }
            if (!route.startsWith("workspace/")) {
                exchange.respond(404, "text/plain", "Not found")
                return
            }
            val encodedRelative = route.removePrefix("workspace/")
            val relative = URLDecoder.decode(encodedRelative.replace("+", "%2B"), StandardCharsets.UTF_8)
            val file = resolveExisting(root, relative)
            if (file == null || !Files.isRegularFile(file)) {
                exchange.respond(404, "text/plain", "Not found")
                return
            }
            val size = Files.size(file)
            require(size <= MAX_ASSET_BYTES) { "Preview asset exceeds the size limit" }
            val isHtml = file.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("html", "htm")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
            exchange.responseHeaders.add("Content-Type", Files.probeContentType(file) ?: mimeType(file))
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
            } else if (isHtml) {
                val html = Files.readString(file)
                val bytes = html.encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(200, size)
                Files.newInputStream(file).use { input -> exchange.responseBody.use { input.copyTo(it) } }
            }
        }

        private fun previewShell(title: String, entryPath: String, entryUrl: String): String = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${escapeHtml(title)}</title><style>
            *{box-sizing:border-box}html,body{height:100%;margin:0;background:#fff;font-family:system-ui,sans-serif;color:#202622}
            body{display:grid;grid-template-rows:74px 1fr}.bar{height:54px;display:flex;align-items:center;margin:10px 14px;padding:5px 8px 5px 18px;border:1px solid rgba(228,229,226,.58);border-radius:27px;background:rgba(255,255,255,.94);box-shadow:0 8px 22px rgba(32,38,34,.14);gap:14px}
            .name{font-size:14px;font-weight:650;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.path{font:11px ui-monospace,monospace;color:#727570}
            .live{display:flex;align-items:center;gap:6px;font-size:11px;color:#3e7653}.live:before{content:'';width:8px;height:8px;border-radius:50%;background:#8fd6a8}button{display:grid;place-items:center;width:40px;height:40px;border:0;border-radius:50%;background:transparent;cursor:pointer;color:#202622;padding:8px}button:hover{background:#f2f3f1}button svg{width:22px;height:22px}
            iframe{width:100%;height:100%;border:0;background:#fff}@media(max-width:640px){.bar{margin-inline:10px;padding-left:14px;gap:8px}.path{display:none}.live{font-size:0}.live:before{display:block}}</style></head><body>
            <header class="bar"><div class="name">${escapeHtml(title)}</div><div class="path">/workspace/${escapeHtml(entryPath)}</div><div class="live">Live</div><button onclick="fetch('background',{method:'POST'})" aria-label="Move preview to background" title="Move to background"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 5.5h14v10H5zM8 18.5h8"/></svg></button><button onclick="document.querySelector('iframe').contentWindow.location.reload()" aria-label="Reload"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19.25 8.25A8 8 0 1 0 19.55 15.2M19.25 4.85v3.4h-3.4"/></svg></button></header>
            <iframe src="${escapeHtml(entryUrl)}" allow="camera; microphone; geolocation; accelerometer; gyroscope; magnetometer; clipboard-read; clipboard-write" title="${escapeHtml(title)}"></iframe></body></html>
        """.trimIndent()

        private fun HttpExchange.respond(status: Int, contentType: String, text: String) {
            val bytes = text.encodeToByteArray()
            responseHeaders.set("Content-Type", contentType)
            responseHeaders.set("Cache-Control", "no-store")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun encodePathPart(value: String): String =
            java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun escapeHtml(value: String): String = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        private fun mimeType(path: Path): String = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }

        private const val MAX_ASSET_BYTES = 32L * 1024L * 1024L

    }

    private fun shutdown() {
        browser?.close()
        server?.stop(0)
    }
}

@OptIn(ExperimentalEncodingApi::class)
private class DesktopChromiumSession private constructor(
    private val process: Process,
    private val userDataDirectory: Path,
    private val webSocketUrl: URI,
) {
    fun captureScreenshot(): ByteArray {
        val response = command("Page.captureScreenshot", """{"format":"png","fromSurface":true,"captureBeyondViewport":false}""")
        val encoded = Json.parseToJsonElement(response).jsonObject["result"]
            ?.jsonObject?.get("data")?.jsonPrimitive?.content
            ?: error("Chromium did not return a Web screenshot")
        return Base64.Default.decode(encoded)
    }

    fun evaluate(expression: String): String {
        val params = buildJsonObject {
            put("expression", expression)
            put("returnByValue", true)
            put("awaitPromise", true)
        }.toString()
        val response = Json.parseToJsonElement(command("Runtime.evaluate", params)).jsonObject
        response["result"]?.jsonObject?.get("exceptionDetails")?.let { details ->
            error(details.jsonObject["text"]?.jsonPrimitive?.content ?: "Web JavaScript evaluation failed")
        }
        return response.getValue("result").jsonObject.getValue("result").jsonObject["value"]
            ?.jsonPrimitive?.content ?: "null"
    }

    fun click(x: Int, y: Int) {
        command("Input.dispatchMouseEvent", """{"type":"mousePressed","x":$x,"y":$y,"button":"left","clickCount":1}""")
        command("Input.dispatchMouseEvent", """{"type":"mouseReleased","x":$x,"y":$y,"button":"left","clickCount":1}""")
    }

    fun reload() {
        command("Page.reload", """{"ignoreCache":true}""")
    }

    fun back() {
        val response = Json.parseToJsonElement(command("Page.getNavigationHistory", "{}")).jsonObject
            .getValue("result").jsonObject
        val current = response.getValue("currentIndex").jsonPrimitive.content.toInt()
        require(current > 0) { "Web container has no page to go back to" }
        val entryId = response.getValue("entries").jsonArray[current - 1].jsonObject
            .getValue("id").jsonPrimitive.content
        command("Page.navigateToHistoryEntry", """{"entryId":$entryId}""")
    }

    fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot {
        val encoded = evaluate(
            "JSON.stringify((window.__kcodeDebugConsole||[]).filter(function(e){return e.sequence>${cursor.coerceAtLeast(0)}}).slice(0,${limit.coerceIn(1, 200)}))",
        )
        val entries = Json.parseToJsonElement(encoded).jsonArray.map { item ->
            val entry = item.jsonObject
            WebConsoleEntry(
                sequence = entry.getValue("sequence").jsonPrimitive.content.toLong(),
                level = entry.getValue("level").jsonPrimitive.content,
                message = entry.getValue("message").jsonPrimitive.content,
                source = entry["source"]?.jsonPrimitive?.contentOrNull,
                line = entry["line"]?.jsonPrimitive?.intOrNull,
            )
        }
        return WebConsoleSnapshot(containerId, entries, entries.lastOrNull()?.sequence ?: cursor)
    }

    fun setState(state: WebContainerState) {
        val windowResponse = command("Browser.getWindowForTarget", "{}")
        val windowId = Json.parseToJsonElement(windowResponse).jsonObject["result"]
            ?.jsonObject?.get("windowId")?.jsonPrimitive?.content
            ?: error("Chromium did not return the Web container window")
        val windowState = if (state == WebContainerState.Foreground) "normal" else "minimized"
        command(
            "Browser.setWindowBounds",
            """{"windowId":$windowId,"bounds":{"windowState":"$windowState"}}""",
        )
        if (state == WebContainerState.Foreground) command("Page.bringToFront", "{}")
    }

    fun close() {
        runCatching { command("Browser.close", "{}") }
        if (process.isAlive) process.destroy()
        runCatching { process.onExit().get(2, TimeUnit.SECONDS) }
        if (process.isAlive) process.destroyForcibly()
        deleteUserDataDirectory(userDataDirectory)
    }

    private fun command(method: String, params: String): String {
        val response = CompletableFuture<String>()
        val buffer = StringBuilder()
        val socket = HTTP_CLIENT.newWebSocketBuilder().buildAsync(
            webSocketUrl,
            object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    buffer.append(data)
                    if (last) {
                        val text = buffer.toString()
                        buffer.clear()
                        if (runCatching { Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content == "1" }.getOrDefault(false)) {
                            response.complete(text)
                        }
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    response.completeExceptionally(error)
                }
            },
        ).get(10, TimeUnit.SECONDS)
        socket.sendText("""{"id":1,"method":"$method","params":$params}""", true).join()
        return try {
            response.get(15, TimeUnit.SECONDS)
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
        }
    }

    companion object {
        private val HTTP_CLIENT: HttpClient = HttpClient.newBuilder().build()

        fun start(previewUri: URI): DesktopChromiumSession? {
            val executable = findChromium() ?: return null
            val userData = Files.createTempDirectory("kcode-web-preview-").toAbsolutePath().normalize()
            val process = runCatching {
                ProcessBuilder(
                    executable.toString(),
                    "--app=$previewUri",
                    "--remote-debugging-port=0",
                    "--user-data-dir=$userData",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-background-mode",
                ).start()
            }.getOrElse {
                deleteUserDataDirectory(userData)
                return null
            }
            return runCatching {
                val port = awaitDevToolsPort(userData, process)
                val socketUrl = awaitPageSocket(port, previewUri)
                DesktopChromiumSession(process, userData, socketUrl).also { it.installDebugging() }
            }.getOrElse {
                process.destroyForcibly()
                deleteUserDataDirectory(userData)
                null
            }
        }

        private fun awaitDevToolsPort(userData: Path, process: Process): Int {
            val portFile = userData.resolve("DevToolsActivePort")
            repeat(200) {
                if (Files.isRegularFile(portFile)) return Files.readAllLines(portFile).first().toInt()
                check(process.isAlive) { "Chromium exited before the Web container opened" }
                Thread.sleep(50)
            }
            error("Timed out waiting for the Web container browser")
        }

        private fun awaitPageSocket(port: Int, previewUri: URI): URI {
            repeat(200) {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list")).GET().build()
                val body = runCatching { HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body() }.getOrNull()
                val targets = body?.let { json ->
                    runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull()?.filter { element ->
                        val item = element.jsonObject
                        item["type"]?.jsonPrimitive?.content == "page"
                    }
                }
                val target = targets?.firstOrNull { element ->
                    element.jsonObject["url"]?.jsonPrimitive?.content == previewUri.toString()
                } ?: targets?.singleOrNull()
                target?.jsonObject?.get("webSocketDebuggerUrl")?.jsonPrimitive?.content?.let { return URI(it) }
                Thread.sleep(50)
            }
            error("Timed out connecting to the Web container page")
        }

        private fun findChromium(): Path? {
            val environment = System.getenv()
            val candidates = buildList {
                environment["PROGRAMFILES(X86)"]?.let { add(Path.of(it, "Microsoft", "Edge", "Application", "msedge.exe")) }
                environment["PROGRAMFILES"]?.let {
                    add(Path.of(it, "Microsoft", "Edge", "Application", "msedge.exe"))
                    add(Path.of(it, "Google", "Chrome", "Application", "chrome.exe"))
                }
                environment["LOCALAPPDATA"]?.let {
                    add(Path.of(it, "Microsoft", "Edge", "Application", "msedge.exe"))
                    add(Path.of(it, "Google", "Chrome", "Application", "chrome.exe"))
                }
                add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
                add(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"))
                add(Path.of("/usr/bin/google-chrome"))
                add(Path.of("/usr/bin/chromium"))
                add(Path.of("/usr/bin/chromium-browser"))
                add(Path.of("/usr/bin/microsoft-edge"))
            }
            return candidates.firstOrNull(Files::isRegularFile)
        }

        private fun deleteUserDataDirectory(directory: Path) {
            val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            val target = directory.toAbsolutePath().normalize()
            if (!target.startsWith(temporaryRoot) || !target.fileName.toString().startsWith("kcode-web-preview-")) return
            if (!Files.exists(target)) return
            Files.walk(target).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun installDebugging() {
        command(
            "Page.addScriptToEvaluateOnNewDocument",
            buildJsonObject { put("source", WebDebugScript.consoleCapture) }.toString(),
        )
        evaluate(WebDebugScript.consoleCapture)
    }
}

private fun resolveExisting(root: Path, relative: String): Path? {
    if (relative.isBlank() || '\\' in relative || '\u0000' in relative) return null
    val segments = relative.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
    val candidate = root.resolve(segments.joinToString(java.io.File.separator)).normalize()
    if (!candidate.startsWith(root) || !Files.exists(candidate)) return null
    val real = candidate.toRealPath()
    return real.takeIf { it.startsWith(root) }
}
