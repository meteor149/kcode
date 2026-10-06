package ai.meteor.kcode.plugin

import ai.meteor.kcode.createAndroidProfileHost
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.plugin.api.AndroidPluginWindow
import ai.meteor.kcode.plugin.api.AndroidPluginWindowContent
import ai.meteor.kcode.plugin.api.AndroidPluginWindowFactory
import ai.meteor.kcode.plugin.api.NativeAndroidPluginWindowHost
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.profiles.KcodeProfiles
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Exercises shipped private APK renderers, not a test-owned ProfileUiSession. */
class AndroidProfileUiRenderingTest {
    @Test(timeout = 180_000)
    fun privateSettingsPageConfirmsEditsAndSavesBeforeReturning(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "profile-ui-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        lateinit var slots: KcodeUiSlots
        lateinit var client: ProfileManagementClient
        val capture = kcodePlugin(PluginDescriptor("provider.ui.compose", "test", "test", emptySet()),
            plugin<Unit>(inject = dependencies(KcodeUiSlots.Key, KcodeProfiles.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
                client = ctx.require(KcodeProfiles.Key).client
            }, Unit)
        val host = withContext(Dispatchers.Main.immediate) {
            val activity = object : Activity() {
                init { attachBaseContext(isolated) }
                override fun getApplicationContext(): android.content.Context = isolated
                override fun getFilesDir() = directory
                override fun getAssets() = context.assets
                override fun getResources() = context.resources
            }
            createAndroidProfileHost(activity, toolCallApprover = ToolCallApprover { true },
                profile = KcodePluginProfile(overrides = listOf(capture)))
        }
        var window: AndroidPluginWindow? = null
        lateinit var view: ComposeView
        val language = mutableStateOf(AppLanguage("en"))
        val shown = mutableStateOf(true)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            val snapshot = slots.snapshot()
            val section = snapshot.settingsSections.single { it.id == "profiles" }
            val renderer = requireNotNull(snapshot.settings)
            val theme = requireNotNull(snapshot.theme)
            assertNotSame(SettingsPageRequest::class.java.classLoader, renderer.javaClass.classLoader)
            assertTrue(host.pluginManager.installed().any { it.id == "provider.ui.settings.profiles" })
            val baseline = client.preview(ProfileTarget("native")).definition
            val active = host.pluginManager.currentProfile()
            window = NativeAndroidPluginWindowHost(context).open(AndroidPluginWindowFactory { activity ->
                object : AndroidPluginWindowContent {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        activity.setShowWhenLocked(true)
                        activity.setTurnScreenOn(true)
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        view = ComposeView(activity)
                        activity.setContentView(view)
                        view.setContent {
                            CompositionLocalProvider(LocalAppLanguage provides language.value,
                                LocalTranslationCatalog provides snapshot.localization) {
                                theme.Render {
                                    if (shown.value) renderer.Render(SettingsPageRequest(
                                        StoredAppSettings(), null, {}, { shown.value = false }, listOf(section),
                                    ))
                                }
                            }
                        }
                    }
                    override suspend fun close() {
                        withContext(Dispatchers.Main.immediate) { view.disposeComposition() }
                    }
                }
            })
            withTimeout(10_000) { window.show(); window.awaitReady() }
            click("Profiles")
            awaitLabel("Refresh")
            awaitLabel("Committed")
            withContext(Dispatchers.Main.immediate) {
                check(nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null }
                    .config[SemanticsActions.ScrollToIndex].action!!.invoke(2))
            }
            awaitLabel("Profile ID")
            withContext(Dispatchers.Main.immediate) {
                check(editor("Profile ID").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("cancelled-import")))
            }
            val beforePicker = client.catalogue()
            withContext(Dispatchers.Main.immediate) {
                check(nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null }
                    .config[SemanticsActions.ScrollToIndex].action!!.invoke(3))
            }
            click("Import Profile file")
            assertTrue(kotlinx.coroutines.withTimeoutOrNull(15_000) {
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") != true) delay(50)
                true
            } == true, "System document picker did not open")
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            withTimeout(15_000) {
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) delay(50)
            }
            withTimeout(15_000) {
                while (!withContext(Dispatchers.Main.immediate) {
                    nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "profile-import-file" &&
                        it.config.getOrNull(SemanticsProperties.Disabled) == null }
                }) delay(50)
            }
            assertEquals(beforePicker, client.catalogue())
            assertEquals(active, host.pluginManager.currentProfile())
            click("Import Bundle archives")
            assertTrue(kotlinx.coroutines.withTimeoutOrNull(15_000) {
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") != true) delay(50)
                true
            } == true, "Bundle document picker did not open")
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            withTimeout(15_000) {
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) delay(50)
                while (!withContext(Dispatchers.Main.immediate) {
                    nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "profile-import-bundles" &&
                        it.config.getOrNull(SemanticsProperties.Disabled) == null }
                }) delay(50)
            }
            assertEquals(beforePicker, client.catalogue())
            assertEquals(active, host.pluginManager.currentProfile())
            scrollEditor()
            setDocument("invalid definition")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitLabel("Save your changes before leaving this profile?")
            delay(400)
            instrumentation.waitForIdleSync()
            withContext(Dispatchers.Main.immediate) {
                for (label in listOf("Save draft and leave", "Discard changes and leave", "Continue editing")) {
                    val action = nodes().first { label in ownLabels(it) }
                    assertTrue(action.boundsInWindow.height + 1f >= action.size.height, "Clipped action: $label")
                    assertTrue(action.boundsInWindow.width + 1f >= action.size.width, "Clipped action: $label")
                }
            }
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue(screenshot.width > 0 && screenshot.height > 0)
                File(instrumentation.context.cacheDir, "profile-ui-confirmation.png").outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            click("Save draft and leave")
            awaitLabel("Invalid definition or changed Profile ID. Correct the document before saving.")
            assertTrue(shown.value)
            assertEquals(active, host.pluginManager.currentProfile())
            // Private resource fallback follows the app language even within a live dialog.
            withContext(Dispatchers.Main.immediate) { language.value = AppLanguage("zh") }
            awaitLabel("离开此 Profile 前是否保存修改？")
            click("继续编辑")
            awaitAbsent("离开此 Profile 前是否保存修改？")
            assertEquals("invalid definition", editorValue("Profile 定义"))
            withContext(Dispatchers.Main.immediate) { language.value = AppLanguage("en") }
            awaitLabel("Profile definition")
            val saved = baseline.copy(displayName = "Saved through private UI")
            setDocument(Json.encodeToString(ProfileDefinition.serializer(), saved))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitLabel("Save your changes before leaving this profile?")
            click("Save draft and leave")
            awaitLabel("Choose and edit your plugin composition")
            assertEquals(saved, client.draft("native"))
            assertEquals(active, host.pluginManager.currentProfile())
            assertTrue(shown.value)
            click("Profiles")
            awaitLabel("Refresh")
            scrollEditor()
            setDocument("discard this")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitLabel("Save your changes before leaving this profile?")
            click("Discard changes and leave")
            awaitLabel("Choose and edit your plugin composition")
            assertEquals(saved, client.draft("native"))
            assertFalse(labels().contains("Save your changes before leaving this profile?"))
            assertTrue(withContext(Dispatchers.Main.immediate) { view.isAttachedToWindow })
        } finally {
            try { window?.close() } finally {
                try { host.close() } finally {
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                    require(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                    directory.deleteRecursively()
                }
            }
        }
    }

    private suspend fun awaitLabel(label: String) {
        assertTrue(kotlinx.coroutines.withTimeoutOrNull(15_000) {
            while (label !in labels()) delay(50)
            true
        } == true, "Missing label: $label")
    }

    private suspend fun awaitAbsent(label: String) = withTimeout(15_000) {
        while (label in labels()) delay(50)
    }

    private suspend fun labels(): List<String> = withContext(Dispatchers.Main.immediate) {
        nodes().flatMap(::ownLabels)
    }

    private suspend fun click(label: String) {
        awaitLabel(label)
        withContext(Dispatchers.Main.immediate) {
            val leaf = nodes().first { label in ownLabels(it) }
            val button = generateSequence(leaf) { it.parent }.first {
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            check(button.config[SemanticsActions.OnClick].action!!.invoke()) { "Click refused: $label" }
        }
    }

    private suspend fun scrollEditor() {
        withTimeout(15_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                nodes().firstOrNull { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null }
                    ?.config?.get(SemanticsActions.ScrollToIndex)?.action?.invoke(4) == true
            }) delay(50)
        }
        awaitLabel("Profile definition")
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                val field = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "profile-definition" }
                val scroll = nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
                val offset = field.positionInRoot.y - scroll.positionInRoot.y - scroll.size.height / 3f
                if (offset > 100f) {
                    check(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, offset))
                    false
                } else true
            }) delay(100)
        }
    }

    private suspend fun setDocument(document: String) {
        awaitLabel("Profile definition")
        withTimeout(15_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "profile-definition" &&
                    it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            }) delay(50)
        }
        withContext(Dispatchers.Main.immediate) {
            check(editor("Profile definition").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(document)))
        }
        withTimeout(10_000) { while (editorValue("Profile definition") != document) delay(50) }
    }

    private suspend fun editorValue(label: String): String = withContext(Dispatchers.Main.immediate) {
        editor(label).config[SemanticsProperties.EditableText].text
    }

    private fun editor(label: String): SemanticsNode {
        fun editable(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsActions.SetText)?.action != null) return node
            return node.children.firstNotNullOfOrNull(::editable)
        }
        val tag = if (label == "Profile ID") "profile-new-id" else "profile-definition"
        val field = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        return requireNotNull(editable(field)) {
            val fieldKeys = field.config.map { it.key.name }
            val childKeys = field.children.map { child -> child.config.map { it.key.name } }
            val editKeys = nodes().filter { node -> node.config.any { it.key.name == "SetText" } }
                .map { node -> node.config.map { it.key.name } }
            "Profile definition field has no enabled edit action ($label): field=$fieldKeys children=$childKeys editors=$editKeys"
        }
    }

    private fun ownLabels(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    /** Dialogs have separate view roots, all owned by this instrumentation process. */
    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) {
            result += node
            node.children.forEach(::collect)
        }
        fun visit(current: View) {
            val method = current.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            val owner = method?.invoke(current) as? SemanticsOwner
            if (owner != null) {
                collect(owner.unmergedRootSemanticsNode)
                collect(owner.rootSemanticsNode)
            }
            else if (current is ViewGroup) repeat(current.childCount) { visit(current.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        return result
    }
}
