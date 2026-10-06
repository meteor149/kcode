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
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.CommittedProfileGeneration
import ai.meteor.kcode.plugin.profiles.ProfileLock
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
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.plugin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidProfileRecoveryUiTest {
    @Test(timeout = 120_000)
    fun historicalSelectionReactivatesFrozenBundleAndAppendsAGeneration(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "history-recovery-${System.nanoTime()}").apply { mkdirs() }
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        val definition = ProfileDefinition(id = "history", bundles = listOf(ProfileBundleReference("example.bundle", "1")))
        val original = ProfileBundle(id = "example.bundle", version = "1", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("root", "example.root", JsonPrimitive("Historical alternative root"), configurationKind = "string"),
        ))))
        val broken = original.copy(patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("root", "missing.module")))))
        repository.commit(CommittedProfileGeneration(generation = 1, definition = definition,
            lock = ProfileLock(), composition = PluginCompositionSnapshot(), bundles = listOf(original)), null)
        repository.commit(CommittedProfileGeneration(generation = 2, definition = definition,
            lock = ProfileLock(), composition = PluginCompositionSnapshot(), bundles = listOf(broken)), 1)
        val host = withContext(Dispatchers.Main.immediate) {
            createAndroidProfileHost(isolatedActivity(directory), profileId = "history",
                profile = KcodePluginProfile(includeDefaults = false), moduleFactories = mapOf("example.root" to {
                    kcodePlugin(PluginDescriptor("example.root", "test", "test", emptySet()), plugin<String> { ctx, label ->
                        KcodeApplicationUi(ctx, ApplicationRenderer {
                            ApplicationFrame { BasicText(label, Modifier.safeDrawingPadding()) }
                        })
                    }, "Default")
                }))
        }
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            withWindow(host) {
                click("History 1")
                awaitLabel("History is read-only. Activate it directly, or copy it to a draft before editing.")
                screenshot("profile-recovery-history.png")
                click("Save and activate")
                awaitLabel("Historical alternative root")
                assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
                val committed = repository.loadCommitted("history")!!
                assertEquals(3L, committed.generation)
                assertEquals(listOf(original), committed.bundles)
                assertEquals(listOf(broken), repository.loadGeneration("history", 2)!!.bundles)
                assertEquals(null, repository.loadDraft("history"))
            }
        } finally { host.close(); directory.deleteRecursively() }
    }

    @Test(timeout = 180_000)
    fun shippedTemplateCreatesAndActivatesASeparateRepairThroughTheRecoverySurface(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "template-recovery-${System.nanoTime()}").apply { mkdirs() }
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        val broken = ProfileDefinition(id = "broken", patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("root", "missing.module")))))
        repository.saveDraft(broken)
        val host = withContext(Dispatchers.Main.immediate) {
            createAndroidProfileHost(isolatedActivity(directory), profileId = "broken")
        }
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            val template = host.profileTemplates.single()
            assertEquals(setOf("kcode.base", "kcode.agent", "kcode.default-ui"), template.bundles.map { it.id }.toSet())
            withWindow(host) {
                awaitLabel("Create a repair copy")
                setField("recovery-copy-id", "repair")
                click("Create from template: native")
                awaitLabel("repair (repair)")
                val draft = repository.loadDraft("repair")!!
                assertEquals(template.bundles, draft.bundles)
                assertEquals(ProfileDataScope(workspace = "profile"), draft.dataScope)
                assertEquals(broken, repository.loadDraft("broken"))
                screenshot("profile-recovery-template.png")
                click("Activate saved selection")
                withTimeout(30_000) {
                    while (host.state.value.phase != ProfileHostPhase.Ready || withContext(Dispatchers.Main.immediate) {
                        val labels = nodes().flatMap(::labels)
                        labels.isEmpty() || "Profile recovery" in labels
                    }) delay(50)
                }
                assertEquals("repair", repository.selected())
                assertEquals(draft, repository.loadCommitted("repair")!!.definition)
                assertEquals(broken, repository.loadDraft("broken"))
                screenshot("profile-recovered-template-root.png")
            }
        } finally { host.close(); directory.deleteRecursively() }
    }

    private fun isolatedActivity(directory: File): Activity {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        return object : Activity() {
            init { attachBaseContext(isolated) }
            override fun getApplicationContext(): android.content.Context = isolated
            override fun getFilesDir() = directory
            override fun getAssets() = context.assets
            override fun getResources() = context.resources
        }
    }

    private suspend fun withWindow(host: KcodeProfileHost, block: suspend () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var window: AndroidPluginWindow? = null
        lateinit var view: ComposeView
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            window = NativeAndroidPluginWindowHost(instrumentation.targetContext).open(AndroidPluginWindowFactory { activity ->
                object : AndroidPluginWindowContent {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        activity.setShowWhenLocked(true)
                        activity.setTurnScreenOn(true)
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        view = ComposeView(activity)
                        activity.setContentView(view)
                        view.setContent { ProfileHostContent(host, ApplicationHostOptions(), "en") }
                    }
                    override suspend fun close() {
                        withContext(Dispatchers.Main.immediate) { view.disposeComposition() }
                    }
                }
            })
            withTimeout(10_000) { window.show(); window.awaitReady() }
            awaitLabel("Profile recovery")
            block()
        } finally {
            try { window?.close() } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
    }

    @Test(timeout = 120_000)
    fun startupFailureCanBeRepairedAndActivatedThroughTheHostSurface(): Unit = runBlocking {
        startupRecoverySurface(failCatalogue = false)
    }

    @Test(timeout = 120_000)
    fun moduleCatalogueFailureCanBeRepairedThroughTheHostSurface(): Unit = runBlocking {
        startupRecoverySurface(failCatalogue = true)
    }

    private suspend fun startupRecoverySurface(failCatalogue: Boolean) {
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
        var catalogueAvailable = !failCatalogue
        var allocations = 0
        val modules = mapOf("example.root" to {
            check(catalogueAvailable) { "Native module catalogue unavailable" }
            kcodePlugin(PluginDescriptor("example.root", "test", "test", emptySet()), plugin<Unit> { ctx, _ ->
                allocations++
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
        assertEquals(0, allocations)
        if (failCatalogue) assertEquals("Native module catalogue unavailable", host.state.value.failure?.message)
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
            assertEquals(0, allocations)
            catalogueAvailable = true
            val repaired = broken.copy(patches = listOf(ProfileOperation.Insert(listOf(ProfileEntry("root", "example.root")))))
            setDocument(Json.encodeToString(ProfileDefinition.serializer(), repaired))
            click("Save and activate")
            awaitLabel("Recovered alternative root")
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals(1, allocations)
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
        setField("recovery-definition", document)
    }

    private suspend fun setField(tag: String, document: String) {
        withTimeout(15_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag && it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            }) delay(50)
        }
        withContext(Dispatchers.Main.immediate) {
            check(field(tag).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(document)))
        }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main.immediate) { field(tag).config[SemanticsProperties.EditableText].text } != document) delay(50)
        }
    }

    private fun field(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun editor() = field("recovery-definition")
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
