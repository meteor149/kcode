@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package app.kcode

import ai.koog.agents.core.tools.ToolRegistry
import app.kcode.h5.H5ContainerLauncher
import app.kcode.h5.H5PreviewRequest
import app.kcode.h5.H5PreviewResult
import app.kcode.h5.H5VirtualPath
import app.kcode.tools.search.WebSearchConfiguration
import app.kcode.tools.search.WebSearchProvider
import app.kcode.tools.search.WebSearchTool
import app.kcode.settings.AppSettingsStore
import app.kcode.settings.ToolPermissionMode
import app.kcode.tools.permission.ToolCallApprover
import app.kcode.tools.io.normalizeWorkspacePath
import kotlin.io.encoding.Base64
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLIFrameElement

internal class WebToolPermissionState(
    var mode: ToolPermissionMode = ToolPermissionMode.Ask,
)

internal fun createWebKoogChatService(
    settingsStore: AppSettingsStore,
    permissionState: WebToolPermissionState,
): KoogChatService {
    val workspace = WebAgentWorkspace()
    return KoogChatService(
        additionalTools = ToolRegistry {
            tool(AgentReadFileTool(workspace))
            tool(AgentListDirectoryTool(workspace))
            tool(AgentWriteFileTool(workspace))
            tool(AgentEditFileTool(workspace))
            tool(H5PreviewTool(WebH5ContainerLauncher(workspace)))
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

private class WebH5ContainerLauncher(
    private val workspace: WebAgentWorkspace,
) : H5ContainerLauncher {
    override suspend fun launch(request: H5PreviewRequest): H5PreviewResult {
        H5VirtualPath.relativeEntry(request.entryPath)
        val source = workspace.readTextOrNull(request.entryPath)
            ?: error("H5 entry does not exist: ${request.entryPath}")
        val html = inlineLocalAssets(source, request.entryPath)
        showPreview(request.title, html)
        return H5PreviewResult(
            entryPath = request.entryPath,
            entrySize = source.encodeToByteArray().size.toLong(),
            presentation = "web-sandboxed-iframe",
        )
    }

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
        val sdk = "<script>$WEB_CAPABILITY_SDK</script>"
        val head = HEAD_PATTERN.find(rendered)
        return if (head == null) sdk + rendered else rendered.replaceRange(head.range, head.value + sdk)
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

    private fun showPreview(title: String, html: String) {
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
        bar.textContent = title.take(80).ifBlank { "H5 Preview" }
        bar.style.height = "52px"
        bar.style.display = "flex"
        bar.style.alignItems = "center"
        bar.style.justifyContent = "space-between"
        bar.style.padding = "0 16px"
        bar.style.font = "600 15px system-ui, sans-serif"
        bar.style.borderBottom = "1px solid #e7e7e4"

        val close = document.createElement("button") as HTMLButtonElement
        close.textContent = "Close"
        close.style.border = "0"
        close.style.borderRadius = "999px"
        close.style.padding = "8px 14px"
        close.style.background = "#202622"
        close.style.color = "white"
        close.onclick = { overlay.remove(); null }
        bar.appendChild(close)

        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.setAttribute("sandbox", "allow-scripts allow-forms allow-modals allow-downloads")
        frame.setAttribute("allow", "camera; microphone; geolocation; accelerometer; gyroscope; magnetometer")
        frame.style.border = "0"
        frame.style.width = "100%"
        frame.style.flex = "1"
        frame.srcdoc = html
        overlay.appendChild(bar)
        overlay.appendChild(frame)
        requireNotNull(document.body).appendChild(overlay)
    }

    private companion object {
        const val PREVIEW_ID = "kcode-h5-preview"
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
        val HEAD_PATTERN = Regex("""<head(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)
        val WEB_CAPABILITY_SDK = """
            (function () {
              if (window.kcode && window.kcode.capabilities) return;
              var subscriptions = new Map();
              var sequence = 0;
              function descriptor(id, available, subscription, sensitive, reason) {
                return { id: id, available: available, subscription: !!subscription, sensitive: !!sensitive,
                  platform: 'web', reason: reason || null };
              }
              function list() {
                return Promise.resolve([
                  descriptor('camera.capture', !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia), false, true),
                  descriptor('camera.pick', true, false, true),
                  descriptor('location.current', !!navigator.geolocation, false, true),
                  descriptor('location.watch', !!navigator.geolocation, true, true),
                  descriptor('sensor.compass', 'DeviceOrientationEvent' in window, true, true),
                  descriptor('sensor.orientation', 'DeviceOrientationEvent' in window, true, true),
                  descriptor('sensor.accelerometer', 'DeviceMotionEvent' in window, true, true),
                  descriptor('sensor.gyroscope', 'DeviceMotionEvent' in window, true, true),
                  descriptor('sensor.magneticField', false, true, true, 'Browser API unavailable'),
                  descriptor('sensor.pressure', false, true, false, 'Browser API unavailable'),
                  descriptor('sensor.light', false, true, false, 'Browser API unavailable'),
                  descriptor('sensor.proximity', false, true, true, 'Browser API unavailable'),
                  descriptor('device.vibrate', typeof navigator.vibrate === 'function', false, true),
                  descriptor('device.flashlight', false, false, true, 'Use a camera MediaStream track directly'),
                  descriptor('device.battery', typeof navigator.getBattery === 'function', false, false),
                  descriptor('device.network', true, false, false),
                  descriptor('device.openSettings', false, false, true, 'Browsers do not expose a settings URI'),
                  descriptor('media.recordAudio', !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia), false, true),
                  descriptor('media.scanQrCode', 'BarcodeDetector' in window, false, true)
                ]);
              }
              function chooseImage(capture) {
                return new Promise(function (resolve, reject) {
                  var input = document.createElement('input');
                  input.type = 'file'; input.accept = 'image/*';
                  if (capture) input.setAttribute('capture', 'environment');
                  input.onchange = function () {
                    var file = input.files && input.files[0];
                    if (!file) { reject(new Error('No image selected')); return; }
                    var reader = new FileReader();
                    reader.onerror = function () { reject(reader.error || new Error('Image read failed')); };
                    reader.onload = function () { resolve({ name: file.name, type: file.type, size: file.size, dataUrl: reader.result }); };
                    reader.readAsDataURL(file);
                  };
                  input.click();
                });
              }
              function currentLocation(params) {
                return new Promise(function (resolve, reject) {
                  if (!navigator.geolocation) { reject(new Error('Geolocation unavailable')); return; }
                  navigator.geolocation.getCurrentPosition(function (position) {
                    resolve({ latitude: position.coords.latitude, longitude: position.coords.longitude,
                      accuracy: position.coords.accuracy, altitude: position.coords.altitude,
                      heading: position.coords.heading, speed: position.coords.speed, timestamp: position.timestamp });
                  }, reject, { enableHighAccuracy: !!(params && params.highAccuracy),
                    timeout: (params && params.timeoutMs) || 15000, maximumAge: 0 });
                });
              }
              function invoke(method, params) {
                params = params || {};
                if (method === 'camera.capture') return chooseImage(true);
                if (method === 'camera.pick') return chooseImage(false);
                if (method === 'location.current') return currentLocation(params);
                if (method === 'device.vibrate') return Promise.resolve({ vibrated: !!(navigator.vibrate && navigator.vibrate(params.durationMs || 100)) });
                if (method === 'device.network') return Promise.resolve({ online: navigator.onLine,
                  type: navigator.connection && navigator.connection.effectiveType || null });
                if (method === 'device.battery' && navigator.getBattery) return navigator.getBattery().then(function (battery) {
                  return { level: battery.level, charging: battery.charging, chargingTime: battery.chargingTime,
                    dischargingTime: battery.dischargingTime };
                });
                return Promise.reject(new Error(method + ' is unavailable in this browser container'));
              }
              function subscribe(method, params, callback) {
                if (typeof callback !== 'function') return Promise.reject(new TypeError('callback is required'));
                var id = 'web-sub-' + (++sequence);
                var dispose;
                if (method === 'location.watch' && navigator.geolocation) {
                  var watch = navigator.geolocation.watchPosition(function (position) {
                    callback({ latitude: position.coords.latitude, longitude: position.coords.longitude,
                      accuracy: position.coords.accuracy, heading: position.coords.heading,
                      speed: position.coords.speed, timestamp: position.timestamp });
                  }, function (error) { callback({ error: error.message }); }, { enableHighAccuracy: true });
                  dispose = function () { navigator.geolocation.clearWatch(watch); };
                } else if (method === 'sensor.compass' || method === 'sensor.orientation') {
                  var orientation = function (event) { callback({ heading: event.webkitCompassHeading || event.alpha,
                    alpha: event.alpha, beta: event.beta, gamma: event.gamma, absolute: event.absolute }); };
                  window.addEventListener('deviceorientation', orientation);
                  dispose = function () { window.removeEventListener('deviceorientation', orientation); };
                } else if (method === 'sensor.accelerometer' || method === 'sensor.gyroscope') {
                  var motion = function (event) { var value = method === 'sensor.gyroscope' ? event.rotationRate : event.accelerationIncludingGravity;
                    callback(value ? { x: value.alpha === undefined ? value.x : value.alpha,
                      y: value.beta === undefined ? value.y : value.beta,
                      z: value.gamma === undefined ? value.z : value.gamma, interval: event.interval } : {}); };
                  window.addEventListener('devicemotion', motion);
                  dispose = function () { window.removeEventListener('devicemotion', motion); };
                } else return Promise.reject(new Error(method + ' subscriptions are unavailable'));
                subscriptions.set(id, dispose);
                return Promise.resolve(Object.freeze({ id: id, unsubscribe: function () { return unsubscribe(id); } }));
              }
              function unsubscribe(id) { var dispose = subscriptions.get(id); if (dispose) dispose(); subscriptions.delete(id); return Promise.resolve({ id: id }); }
              window.kcode = Object.freeze({ capabilities: Object.freeze({ list: list, invoke: invoke,
                subscribe: subscribe, unsubscribe: unsubscribe }), version: '1.0.0' });
              window.dispatchEvent(new CustomEvent('kcode-ready'));
            })();
        """.trimIndent()
    }
}
