package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.webcontainer.native.AndroidNativeWebContainerPlugin
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionAction
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import android.app.Activity
import android.content.Context
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNativeWebContainerTest {
    @Test(timeout = 90_000)
    fun actualApkOwnsIndependentWebViewsAndWithdrawalJoinsNativeWorkers(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "web-plugins-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "web.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val workspace = File(context.filesDir, "agent_workspace/private-web-test").apply { mkdirs() }
        File(workspace, "style.css").writeText("button { color: rgb(10, 20, 30); }")
        File(workspace, "app.js").writeText("""
            document.getElementById('ready').textContent='ready'; console.log('external-script-ready');
            const dispatch=window.__kcodeDispatch;
            window.__kcodeDispatch=function(message) {
                const payload=typeof message==='string'?JSON.parse(message):message;
                if(payload.id==='test-battery' && payload.result) console.log('native-battery-ready');
                if(dispatch) dispatch(message);
            };
        """.trimIndent())
        File(workspace, "index.html").writeText("""
            <!doctype html><html><head><link rel="stylesheet" href="style.css"></head><body>
            <span id="ready">loading</span><button onclick="console.log('private-click'); window.__kcodeNativeBridge.postMessage(JSON.stringify({id:'test-battery',type:'invoke',method:'device.battery'}))">Run private</button>
            <script src="app.js"></script></body></html>
        """.trimIndent())
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): Context = context }
        }
        var firstController: WebContainerController? = null
        var secondController: WebContainerController? = null
        suspend fun runtime(capture: (WebContainerController?) -> Unit): KcodePluginRuntime = KcodePluginRuntime.create(
            KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { false }),
                hostInputs = AndroidPluginHostInputs(activity),
                featurePlugins = listOf(kcodePlugin(descriptor("test.web.capture"), plugin<Unit>(
                    name = "capture-private-web", inject = dependencies(KcodeWebContainers.Key),
                ) { ctx, _ -> capture(ctx.require(KcodeWebContainers.Key).controller) }, Unit)),
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                    AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
                },
            ),
        )
        val first = runtime { firstController = it }
        val second = runtime { secondController = it }
        val specification = DynamicPluginSpec(
            id = "provider.web-containers.platform", version = "test", artifactPath = artifact.path,
            sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
            entryClass = AndroidNativeWebContainerPlugin::class.java.name, config = Unit,
        )
        val release = CompletableDeferred<Unit>()
        val releaseDestroyed = CompletableDeferred<Unit>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        val power = context.getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val screen = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "kcode:test-private-web")
        try {
            // The assertions require resumed windows; keep the display awake until both windows retire.
            screen.acquire(90_000)
            withTimeout(3_000) { while (!power.isInteractive) delay(50) }
            first.pluginManager.replace(specification)
            second.pluginManager.replace(specification)
            val a = requireNotNull(firstController)
            val b = requireNotNull(secondController)
            assertNotSame(a.javaClass.classLoader, AndroidNativeWebContainerPlugin::class.java.classLoader)
            assertNotSame(a.javaClass.classLoader, b.javaClass.classLoader)
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cmd power wakeup"),
            ).bufferedReader().use { it.readText() }
            val preview = WebPreviewRequest("/workspace/private-web-test/index.html", "Private APK Web")
            val launchedA = withTimeout(15_000) { a.launch(preview) }
            val launchedB = withTimeout(15_000) { b.launch(preview) }
            assertEquals(listOf(launchedA.containerId), a.list().map { it.id })
            assertEquals(listOf(launchedB.containerId), b.list().map { it.id })
            val contentA = content(a, launchedA.containerId)
            val contentB = content(b, launchedB.containerId)
            assertNotSame(contentA.javaClass.classLoader, AndroidNativeWebContainerPlugin::class.java.classLoader)
            assertEquals(a.javaClass.classLoader, contentA.javaClass.classLoader)
            val viewA = field(contentA, "webView") as WebView
            val viewB = field(contentB, "webView") as WebView
            val resourcesA = field(field(field(a, "delegate"), "resources"), "directory") as File
            val resourcesB = field(field(field(b, "delegate"), "resources"), "directory") as File
            assertTrue(resourcesA.isDirectory && resourcesB.isDirectory)
            assertNotSame(viewA, viewB)
            withContext(Dispatchers.Main.immediate) {
                viewA.keepScreenOn = true
                viewB.keepScreenOn = true
                assertTrue(viewA.settings.javaScriptEnabled)
                assertTrue(viewA.webChromeClient != null)
                assertFalse(viewA.settings.allowFileAccess)
                assertFalse(viewA.settings.allowContentAccess)
            }
            withTimeout(10_000) {
                while (a.list().single().state != WebContainerState.Background ||
                    b.list().single().state != WebContainerState.Foreground) delay(50)
            }
            assertNotSame((contentA as Context).assets, context.assets)
            assertNotSame(contentA.assets, contentB.let { (it as Context).assets })
            withTimeout(10_000) {
                while (a.inspect(launchedA.containerId).elements.none { it.name == "Run private" }) delay(50)
            }
            val button = a.inspect(launchedA.containerId).elements.single { it.name == "Run private" }
            a.interact(WebInteractionRequest(launchedA.containerId, WebInteractionAction.Click, handle = button.handle))
            withTimeout(5_000) {
                while (a.console(launchedA.containerId, 0, 30).entries.none { it.message.contains("private-click") }) delay(50)
            }
            withTimeout(5_000) {
                while (a.console(launchedA.containerId, 0, 30).entries.none { it.message.contains("native-battery-ready") }) delay(50)
            }
            assertTrue(a.console(launchedA.containerId, 0, 30).entries.any { it.message.contains("external-script-ready") })
            val screenshot = withTimeout(5_000) { a.screenshot(launchedA.containerId) }
            assertTrue(screenshot.pngBytes.size > 100)
            assertTrue(screenshot.width > 0 && screenshot.height > 0)
            val bridge = field(contentA, "capabilityBridge")
            assertEquals(a.javaClass.classLoader, bridge.javaClass.classLoader)
            val bridgeScope = field(bridge, "scope") as CoroutineScope
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val worker = bridgeScope.launch {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
            entered.await()
            val withdrawal = async { first.pluginManager.setEnabled(specification.id, false) }
            withTimeout(5_000) { cleaning.await() }
            assertFalse(withdrawal.isCompleted)
            assertFalse(worker.isCompleted)
            release.complete(Unit)
            withTimeout(10_000) { withdrawal.await() }
            assertTrue(worker.isCompleted)
            assertFalse(resourcesA.exists())
            assertTrue(resourcesB.isDirectory)
            val closedAsset = assertFailsWith<RuntimeException> { contentA.assets.open("retired-plugin-asset") }
            assertTrue(closedAsset.message.orEmpty().contains("closed", ignoreCase = true))
            assertTrue(field(contentA, "resourcesClosed") as Boolean)
            assertFalse(field(contentB, "resourcesClosed") as Boolean)
            assertEquals(listOf(launchedB.containerId), b.list().map { it.id })
            assertFailsWith<IllegalStateException> { a.list() }
            assertFailsWith<IllegalStateException> { a.inspect(launchedA.containerId) }
            first.pluginManager.setEnabled(specification.id, true)
            val restored = requireNotNull(firstController)
            assertNotSame(a, restored)
            assertTrue(restored.list().isEmpty())
            val replacement = withTimeout(15_000) { restored.launch(preview) }
            assertEquals(listOf(replacement.containerId), restored.list().map { it.id })
            val replacedContent = content(restored, replacement.containerId)
            val replacedResources = field(field(field(restored, "delegate"), "resources"), "directory") as File
            val destroyedBridgeScope = field(field(replacedContent, "capabilityBridge"), "scope") as CoroutineScope
            val destroyedEntered = CompletableDeferred<Unit>()
            val destroyedCleaning = CompletableDeferred<Unit>()
            val destroyedWorker = destroyedBridgeScope.launch {
                destroyedEntered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { destroyedCleaning.complete(Unit); releaseDestroyed.await() }
                }
            }
            destroyedEntered.await()
            withContext(Dispatchers.Main.immediate) { (field(replacedContent, "activity") as Activity).finish() }
            withTimeout(5_000) { destroyedCleaning.await() }
            assertTrue(restored.list().isEmpty())
            val removalStarted = CompletableDeferred<Unit>()
            val removal = async { removalStarted.complete(Unit); first.pluginManager.uninstall(specification.id) }
            removalStarted.await()
            @Suppress("UNCHECKED_CAST") val ownerLive = field(field(restored, "owner"), "live") as StateFlow<Boolean>
            withTimeout(5_000) { while (ownerLive.value) delay(10) }
            assertTrue(replacedResources.isDirectory)
            assertFalse(removal.isCompleted)
            releaseDestroyed.complete(Unit)
            withTimeout(10_000) { removal.await() }
            assertTrue(destroyedWorker.isCompleted)
            assertFalse(replacedResources.exists())
            assertFailsWith<IllegalStateException> { restored.list() }
            b.closeAll()
            b.closeAll()
            assertTrue(b.list().isEmpty())
        } finally {
            release.complete(Unit)
            releaseDestroyed.complete(Unit)
            try { first.close() } finally {
                try { second.close() } finally {
                    if (screen.isHeld) screen.release()
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                    directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
                    workspace.walkBottomUp().forEach { it.delete() }
                }
            }
        }
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private fun field(value: Any, name: String): Any = value.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        requireNotNull(it.get(value))
    }
    private fun content(controller: WebContainerController, id: String): Any {
        val sessions = field(field(controller, "delegate"), "sessions")
        @Suppress("UNCHECKED_CAST") val records = field(sessions, "sessions") as Map<String, Any>
        return field(requireNotNull(records[id]), "content")
    }
}
