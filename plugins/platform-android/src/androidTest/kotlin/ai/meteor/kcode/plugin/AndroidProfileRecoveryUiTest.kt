package ai.meteor.kcode.plugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.createAndroidProfileHost
import ai.meteor.kcode.plugin.api.AndroidPluginWindow
import ai.meteor.kcode.plugin.api.AndroidPluginWindowContent
import ai.meteor.kcode.plugin.api.AndroidPluginWindowFactory
import ai.meteor.kcode.plugin.api.ApplicationFrame
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.NativeAndroidPluginWindowHost
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.recovery.ProfileHostContent
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.Modifier
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
import org.cordis.plugin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidProfileRecoveryUiTest {
    @Test(timeout = 120_000)
    fun startupFailureCanBeRepairedAndActivatedThroughTheHostSurface(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "profile-recovery-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        val broken = ProfileDefinition(id = "broken", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("root", "missing.module"))),
        ))
        repository.saveDraft(broken)
        val modules = mapOf("example.root" to {
            kcodePlugin(PluginDescriptor("example.root", "test", "test", emptySet()), plugin<Unit> { ctx, _ ->
                KcodeApplicationUi(ctx, ApplicationRenderer {
                    ApplicationFrame { BasicText("Recovered alternative root", Modifier.safeDrawingPadding()) }
                })
            }, Unit)
        })
        val host = withContext(Dispatchers.Main.immediate) {
            val activity = object : Activity() {
                init { attachBaseContext(isolated) }
                override fun getApplicationContext(): android.content.Context = isolated
                override fun getFilesDir() = directory
                override fun getAssets() = context.assets
                override fun getResources() = context.resources
            }
            createAndroidProfileHost(activity, profileId = "broken", profile = KcodePluginProfile(includeDefaults = false),
                moduleFactories = modules)
        }
        assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
        var window: AndroidPluginWindow? = null
        lateinit var view: ComposeView
        val language = mutableStateOf("en")
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            window = NativeAndroidPluginWindowHost(context).open(AndroidPluginWindowFactory { activity ->
                object : AndroidPluginWindowContent {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        activity.setShowWhenLocked(true)
                        activity.setTurnScreenOn(true)
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        view = ComposeView(activity)
                        activity.setContentView(view)
                        view.setContent { ProfileHostContent(host, ApplicationHostOptions(), language.value) }
                    }
                    override suspend fun close() {
                        withContext(Dispatchers.Main.immediate) { view.disposeComposition() }
                    }
                }
            })
            withTimeout(10_000) { window.show(); window.awaitReady() }
            awaitLabel("Profile recovery")
            awaitEditor()
            withContext(Dispatchers.Main.immediate) { language.value = "zh" }
            awaitLabel("Profile 恢复")
            withContext(Dispatchers.Main.immediate) { language.value = "en" }
            awaitLabel("Profile recovery")
            screenshot("profile-recovery.png")
            setDocument("invalid JSON")
            click("Save and activate")
            val invalidMessage = requireNotNull(runCatching {
                Json.decodeFromString(ProfileDefinition.serializer(), "invalid JSON")
            }.exceptionOrNull()?.message)
            awaitLabel(invalidMessage)
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main.immediate) { editor().config.getOrNull(SemanticsActions.SetText)?.action == null }) delay(50)
            }
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertEquals(broken, repository.loadDraft("broken"))
            val repaired = broken.copy(patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("root", "example.root")))))
            setDocument(Json.encodeToString(ProfileDefinition.serializer(), repaired))
            click("Save and activate")
            awaitLabel("Recovered alternative root")
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals(repaired, repository.loadCommitted("broken")!!.definition)
            assertEquals("broken", repository.selected())
            screenshot("profile-recovered-root.png")
        } finally {
            try { window?.close() } finally {
                try { host.close() } finally {
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                    directory.deleteRecursively()
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.context.cacheDir, name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun awaitLabel(label: String) = withTimeout(15_000) {
        while (!withContext(Dispatchers.Main.immediate) { nodes().any { label in labels(it) } }) delay(50)
    }

    private suspend fun awaitEditor() = withTimeout(15_000) {
        while (!withContext(Dispatchers.Main.immediate) { nodes().any { it.config.getOrNull(SemanticsActions.SetText)?.action != null } }) delay(50)
    }

    private suspend fun click(label: String) {
        awaitLabel(label)
        withContext(Dispatchers.Main.immediate) {
            val leaf = nodes().first { label in labels(it) }
            val button = generateSequence(leaf) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            check(button.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }

    private suspend fun setDocument(document: String) {
        awaitEditor()
        withContext(Dispatchers.Main.immediate) {
            check(editor().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(document)))
        }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main.immediate) { editor().config[SemanticsProperties.EditableText].text } != document) delay(50)
        }
    }

    private fun editor() = nodes().first { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
    private fun labels(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) { result += node; node.children.forEach(::collect) }
        fun visit(view: View) {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            val owner = method?.invoke(view) as? SemanticsOwner
            if (owner != null) collect(owner.rootSemanticsNode)
            else if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        return result
    }
}
