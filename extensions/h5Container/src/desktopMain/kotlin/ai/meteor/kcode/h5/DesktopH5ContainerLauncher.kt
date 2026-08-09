package ai.meteor.kcode.h5

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopH5ContainerLauncher(
    private val workspaceRoot: Path,
) : H5ContainerLauncher {
    override suspend fun launch(request: H5PreviewRequest): H5PreviewResult = withContext(Dispatchers.IO) {
        val root = Files.createDirectories(workspaceRoot).toRealPath(LinkOption.NOFOLLOW_LINKS)
        val relative = H5VirtualPath.relativeEntry(request.entryPath)
        val entry = resolveExisting(root, relative) ?: error("H5 entry does not exist: ${request.entryPath}")
        require(Files.isRegularFile(entry)) { "H5 entry is not a file" }
        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            "The desktop environment cannot open an H5 preview window"
        }

        val session = DesktopPreviewSession.start(root, relative, request.title)
        Desktop.getDesktop().browse(session.previewUri)
        H5PreviewResult(request.entryPath, Files.size(entry), "desktop-local-preview")
    }
}

private class DesktopPreviewSession private constructor(
    private val server: HttpServer,
    val previewUri: URI,
) {
    companion object {
        private var active: DesktopPreviewSession? = null

        @Synchronized
        fun start(root: Path, entryPath: String, title: String): DesktopPreviewSession {
            active?.server?.stop(0)
            val token = UUID.randomUUID().toString().replace("-", "")
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            val prefix = "/$token/"
            server.createContext(prefix) { exchange ->
                runCatching { handleRequest(exchange, root, prefix, entryPath, title) }
                    .onFailure { exchange.respond(500, "text/plain; charset=utf-8", "Preview error") }
            }
            server.executor = Executors.newCachedThreadPool { task ->
                Thread(task, "kcode-h5-preview").apply { isDaemon = true }
            }
            server.start()
            val host = server.address.address.hostAddress.let { if (':' in it) "[$it]" else it }
            return DesktopPreviewSession(server, URI("http://$host:${server.address.port}${prefix}preview")).also {
                active = it
            }
        }

        private fun handleRequest(exchange: HttpExchange, root: Path, prefix: String, entryPath: String, title: String) {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.respond(405, "text/plain", "Method not allowed")
                return
            }
            val route = exchange.requestURI.rawPath.removePrefix(prefix)
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
                val html = injectDesktopBridge(Files.readString(file))
                val bytes = html.encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(200, size)
                Files.newInputStream(file).use { input -> exchange.responseBody.use { input.copyTo(it) } }
            }
        }

        private fun injectDesktopBridge(html: String): String {
            val script = "<script>$DESKTOP_BRIDGE_BOOTSTRAP\n$KCODE_H5_SDK</script>"
            val headEnd = html.indexOf("</head>", ignoreCase = true)
            return if (headEnd >= 0) html.substring(0, headEnd) + script + html.substring(headEnd) else script + html
        }

        private fun previewShell(title: String, entryPath: String, entryUrl: String): String = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${escapeHtml(title)}</title><style>
            *{box-sizing:border-box}html,body{height:100%;margin:0;background:#fff;font-family:system-ui,sans-serif;color:#202622}
            body{display:grid;grid-template-rows:56px 1fr}.bar{display:flex;align-items:center;padding:0 18px;border-bottom:1px solid #e4e5e2;gap:14px}
            .name{font-size:14px;font-weight:650;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.path{font:11px ui-monospace,monospace;color:#727570}
            .live{font-size:11px;color:#3e7653}.live:before{content:'●';color:#8fd6a8;margin-right:6px}button{border:0;background:transparent;font-size:20px;cursor:pointer;color:#202622;padding:8px}
            iframe{width:100%;height:100%;border:0;background:#fff}</style></head><body>
            <header class="bar"><div class="name">${escapeHtml(title)}</div><div class="path">/workspace/${escapeHtml(entryPath)}</div><div class="live">Live</div><button onclick="document.querySelector('iframe').contentWindow.location.reload()" aria-label="Reload">↻</button></header>
            <iframe src="${escapeHtml(entryUrl)}" allow="clipboard-read; clipboard-write" title="${escapeHtml(title)}"></iframe></body></html>
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

        private val DESKTOP_BRIDGE_BOOTSTRAP = """
            (function () {
              var capabilities = [
                'camera.capture','camera.pick','location.current','location.watch','sensor.compass',
                'sensor.orientation','sensor.accelerometer','sensor.gyroscope','sensor.magneticField',
                'sensor.pressure','sensor.light','sensor.proximity','device.vibrate','device.flashlight',
                'device.battery','device.network','device.openSettings','media.recordAudio','media.scanQrCode'
              ].map(function (id) {
                return { id:id, available:false, subscription:id === 'location.watch' || id.indexOf('sensor.') === 0,
                  sensitive:true, platform:'desktop', reason:'This hardware capability is unavailable on desktop' };
              });
              window.kcodeNative = { postMessage:function (raw) {
                var request = JSON.parse(raw), response;
                if (request.type === 'list') response = { type:'response', id:request.id, result:capabilities };
                else response = { type:'response', id:request.id, error:{ code:'not_supported', message:'Capability '+(request.method || '')+' is unavailable on desktop' } };
                setTimeout(function () { window.__kcodeDispatch(response); }, 0);
              }};
            })();
        """.trimIndent()
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
