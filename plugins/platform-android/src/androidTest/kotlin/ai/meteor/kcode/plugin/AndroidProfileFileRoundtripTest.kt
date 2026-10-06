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
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveWriter
import ai.meteor.kcode.plugin.profiles.ProfileBundleArchiveInput
import org.cordis.packages.PackageTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.ContextWrapper
import android.os.Bundle
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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Exercises shipped private APK renderers, not a test-owned ProfileUiSession. */
class AndroidProfileFileRoundtripTest {
    @Test(timeout = 300_000)
    fun privateApkUiSavesAndSelectsJsonAndCompleteArchiveThroughDocumentsUi(): Unit = runBlocking {
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
        val token = "kcode-picker-${System.nanoTime()}"
        val jsonName = "$token.json"
        val archiveName = "$token.kprofile"
        val bundleUris = mutableListOf<android.net.Uri>()
        var window: AndroidPluginWindow? = null
        lateinit var view: ComposeView
        val language = mutableStateOf(AppLanguage("en"))
        val shown = mutableStateOf(true)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            val root = requireNotNull(host.profileCommands)
            val source = ProfileDefinition(id = "picker-source", patches = listOf(ProfileOperation.Insert(listOf(
                ProfileEntry("dictionary", "feature.localization", Json.parseToJsonElement("""{"defaultLanguage":"en"}""")),
            ))))
            val written = root.writeDraft(ProfileDraftWrite(source, root.catalogue().revision))
            host.pluginManager.activateProfile(ProfileActivationRequest(ProfileTarget(source.id, ProfileSource.Draft), written.revision))
            host.pluginManager.activateProfile(ProfileActivationRequest(ProfileTarget("native"), root.catalogue().revision))
            val expected = ProfilePortableExporter.decode(root.exportPortable(ProfilePortableExport(ProfileTarget(source.id), root.catalogue().revision)))
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
            selectCommitted(source.id)
            val before = root.catalogue()
            scrollIndex(4)
            click("Export Profile file")
            saveDocument(jsonName)
            awaitLabel("Profile file exported.")
            assertEquals(expected, ProfilePortableExporter.decode(readDownload(jsonName).toString(Charsets.UTF_8)))
            assertEquals(before, root.catalogue())
            assertEquals(active, host.pluginManager.currentProfile())
            scrollIndex(3)
            withContext(Dispatchers.Main.immediate) {
                check(editor("Profile ID").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("picker-json")))
            }
            scrollIndex(4)
            click("Import Profile file")
            openDocument(jsonName)
            awaitLabel("Imported as a draft. Preview and activate when ready.")
            val jsonDraft = requireNotNull(root.draft("picker-json"))
            assertEquals(expected.definition.patches, jsonDraft.patches)
            assertEquals(active, host.pluginManager.currentProfile())
            assertEquals(before.revision + 1, root.catalogue().revision)

            selectCommitted(source.id)
            scrollIndex(5)
            click("Export complete Profile archive")
            saveDocument(archiveName)
            awaitLabel("Profile file exported.")
            val savedArchive = File(directory, "selected.kprofile").apply { writeBytes(readDownload(archiveName)) }
            val archived = java.util.zip.ZipFile(savedArchive).use { zip ->
                ProfilePortableExporter.decode(zip.getInputStream(zip.getEntry("profile.json")).bufferedReader().use { it.readText() })
            }
            assertEquals(expected, archived)
            scrollIndex(4)
            withContext(Dispatchers.Main.immediate) {
                check(editor("Profile ID").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("picker-archive")))
            }
            scrollIndex(5)
            click("Import complete Profile archive")
            openDocument(archiveName)
            awaitLabel("Imported as a draft. Preview and activate when ready.")
            val archiveDraft = requireNotNull(root.draft("picker-archive"))
            assertEquals(expected.definition.patches, archiveDraft.patches)
            assertEquals(expected.definition.bundles, archiveDraft.bundles)
            assertEquals(jsonDraft.patches, archiveDraft.patches)
            assertEquals(before.revision + 2, root.catalogue().revision)
            assertEquals(active, host.pluginManager.currentProfile())

            val base = ProfileBundle(id = "picker.base", version = "1.0.0", patches = listOf(
                ProfileOperation.Insert(listOf(ProfileEntry("dictionary", "feature.localization",
                    Json.parseToJsonElement("""{"defaultLanguage":"en"}""")))),
            ))
            val override = ProfileBundle(id = "picker.override", version = "1.0.0", patches = listOf(
                ProfileOperation.Configure("dictionary", Json.parseToJsonElement("""{"defaultLanguage":"zh"}""")),
            ))
            val release = requireNotNull(host.pluginManager.installed().single { it.id == "feature.localization" }.packageInstallation)
            val baseFile = File(directory, "$token-z-base.kbundle")
            val overrideFile = File(directory, "$token-a-override.kbundle")
            val targets = listOf(PackageTarget("android", listOf("arm", "x86")))
            ProfileBundleArchiveWriter().pack(base, listOf(ProfileBundleArchiveInput(File(release.archivePath), release.archiveSha256)), targets, baseFile)
            ProfileBundleArchiveWriter().pack(override, emptyList(), targets, overrideFile)
            bundleUris += publishDownload(baseFile, token)
            bundleUris += publishDownload(overrideFile, token)
            scrollIndex(5)
            withContext(Dispatchers.Main.immediate) {
                check(editor("Profile ID").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("picker-bundles")))
            }
            val beforeBundles = root.catalogue()
            scrollIndex(6)
            click("Import Bundle archives")
            openMultipleDocuments(token, overrideFile.name, baseFile.name)
            awaitLabel("Bundle order")
            assertEquals(beforeBundles, root.catalogue())
            assertEquals(null, root.draft("picker-bundles"))
            val baseToken = bundleToken(baseFile.name)
            val overrideToken = bundleToken(overrideFile.name)
            // Change the returned picker order, then explicitly ensure the inserting layer precedes its override.
            val bottom = withContext(Dispatchers.Main.immediate) {
                nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) in setOf(
                    "profile-bundle-selected-$baseToken", "profile-bundle-selected-$overrideToken") }
                    .maxBy { it.positionInRoot.y }.config[SemanticsProperties.TestTag].substringAfterLast('-')
            }
            clickTag("profile-bundle-up-$bottom")
            delay(100)
            instrumentation.waitForIdleSync()
            val baseCanMoveUp = withContext(Dispatchers.Main.immediate) {
                nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "profile-bundle-up-$baseToken" }
                    .config.getOrNull(SemanticsProperties.Disabled) == null
            }
            if (baseCanMoveUp) clickTag("profile-bundle-up-$baseToken")
            click("Import in this order")
            awaitLabel("Imported as a draft. Preview and activate when ready.")
            val bundlesDraft = requireNotNull(root.draft("picker-bundles"))
            assertEquals(listOf(ProfileBundleReference(base.id, base.version), ProfileBundleReference(override.id, override.version)), bundlesDraft.bundles)
            val preview = root.preview(ProfileTarget("picker-bundles", ProfileSource.Draft))
            assertTrue(preview.packagesVerified)
            assertEquals(Json.parseToJsonElement("""{"defaultLanguage":"zh"}"""), preview.entries.single().config)
            assertEquals(beforeBundles.revision + 1, root.catalogue().revision)
            assertEquals(active, host.pluginManager.currentProfile())
        } finally {
            if (pickerNodes().any { it.packageName?.toString()?.contains("documentsui") == true }) {
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
            try { window?.close() } finally {
                try { host.close() } finally {
                    deleteDownload(jsonName)
                    deleteDownload(archiveName)
                    bundleUris.forEach { context.contentResolver.delete(it, null, null) }
                    require(token.matches(Regex("kcode-picker-[0-9]+")))
                    val cleanup = instrumentation.uiAutomation.executeShellCommand("rmdir /sdcard/Download/$token")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(cleanup).use { it.readBytes() }
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                    require(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                    directory.deleteRecursively()
                }
            }
        }
    }

    private suspend fun scrollIndex(index: Int) = withTimeout(15_000) {
        while (!withContext(Dispatchers.Main.immediate) {
            nodes().firstOrNull { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null }
                ?.config?.get(SemanticsActions.ScrollToIndex)?.action?.invoke(index) == true
        }) delay(50)
    }

    private suspend fun selectCommitted(id: String) {
        scrollIndex(2)
        withTimeout(30_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                val button = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "profile-committed-$id" }
                    ?: return@withContext false
                if (button.config.getOrNull(SemanticsProperties.Disabled) != null) return@withContext false
                check(button.config[SemanticsActions.OnClick].action!!.invoke())
                true
            }) delay(50)
        }
    }

    private fun pickerNodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(::collect) }
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::collect)
        return result
    }

    private suspend fun pickerNode(label: String, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo {
        return try { withTimeout(20_000) {
            while (true) {
                pickerNodes().firstOrNull(predicate)?.let { return@withTimeout it }
                delay(100)
            }
            error("unreachable")
        } } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { File(instrumentation.context.cacheDir, "profile-picker-failure.png").outputStream().use {
                    check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { screenshot.recycle() }
            }
            val controls = pickerNodes().filter { it.isClickable || it.className?.toString() == "android.widget.EditText" }
                .map { "${it.packageName}:${it.viewIdResourceName}:${it.className}:${it.contentDescription}" }
            throw AssertionError("Missing $label; controls=$controls", failure)
        }
    }

    private suspend fun pickerClick(node: android.view.accessibility.AccessibilityNodeInfo) = withTimeout(10_000) {
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        val className = node.className?.toString()
        while (true) {
            val refreshed = if (node.refresh()) node else pickerNodes().firstOrNull {
                it.text?.toString() == text && it.contentDescription?.toString() == description && it.className?.toString() == className
            }
            val clickable = refreshed?.let { generateSequence(it) { parent -> parent.parent }.firstOrNull { it.isClickable && it.isEnabled } }
            if (clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
            delay(100)
        }
    }

    private suspend fun downloads() {
        pickerNode("DocumentsUI window") { it.packageName?.toString()?.contains("documentsui") == true }
        delay(300)
        val navigation = pickerNodes().firstOrNull {
            it.contentDescription?.toString() in setOf("显示根目录", "Show roots") && it.isClickable
        }
        if (navigation != null) pickerClick(navigation)
        pickerClick(pickerNode("Downloads root") { it.text?.toString() in setOf("Downloads", "下载", "下载内容") &&
            generateSequence(it) { node -> node.parent }.any { node -> node.isClickable && node.isEnabled } })
        delay(300)
    }

    private suspend fun saveDocument(name: String) {
        downloads()
        val field = pickerNode("filename field") { it.className?.toString() == "android.widget.EditText" }
        val arguments = Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }
        check(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        pickerClick(pickerNode("Save action") { it.text?.toString() in setOf("Save", "SAVE", "保存") && it.isEnabled })
        withTimeout(20_000) {
            while (pickerNodes().any { it.packageName?.toString()?.contains("documentsui") == true }) delay(100)
        }
    }

    private suspend fun openDocument(name: String) {
        downloads()
        pickerClick(pickerNode("selected document") { it.text?.toString() == name })
        withTimeout(20_000) {
            while (pickerNodes().any { it.packageName?.toString()?.contains("documentsui") == true }) delay(100)
        }
    }

    private suspend fun openMultipleDocuments(folder: String, first: String, second: String) {
        downloads()
        pickerClick(pickerNode("test Bundle directory") { it.text?.toString() == folder })
        pickerNode("first Bundle") { it.text?.toString() == first }
        pickerNode("second Bundle") { it.text?.toString() == second }
        pickerClick(pickerNode("file selection menu") { it.contentDescription?.toString() in setOf("更多选项", "More options") })
        pickerClick(pickerNode("Select all test Bundles") { it.text?.toString() in setOf("全选", "选择全部", "Select all") })
        pickerClick(pickerNode("Open selected Bundles") {
            (it.text?.toString() in setOf("Open", "OPEN", "打开", "Select", "SELECT", "选择") ||
                it.contentDescription?.toString() in setOf("Open", "打开", "Select", "选择")) &&
                generateSequence(it) { node -> node.parent }.any { node -> node.isClickable && node.isEnabled }
        })
    }
    private suspend fun bundleToken(name: String): Int = withContext(Dispatchers.Main.immediate) {
        fun contains(node: SemanticsNode): Boolean = name in ownLabels(node) || node.children.any(::contains)
        nodes().first { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("profile-bundle-selected-") == true && contains(it) }
            .config[SemanticsProperties.TestTag].substringAfterLast('-').toInt()
    }

    private suspend fun clickTag(tag: String) = withContext(Dispatchers.Main.immediate) {
        val node = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        check(node.config.getOrNull(SemanticsProperties.Disabled) == null)
        check(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun publishDownload(file: File, folder: String): android.net.Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) { resolver.delete(uri, null, null); throw failure }
    }

    private fun readDownload(name: String): ByteArray {
        require(name.matches(Regex("kcode-picker-[0-9]+\\.(json|kprofile)")))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/$name")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.readNBytes(64 * 1024 * 1024 + 1).also { require(it.size in 1..64 * 1024 * 1024) }
        }
    }

    private fun deleteDownload(name: String) {
        require(name.matches(Regex("kcode-picker-[0-9]+\\.(json|kprofile)")))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("rm -f /sdcard/Download/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    private suspend fun awaitLabel(label: String) {
        assertTrue(kotlinx.coroutines.withTimeoutOrNull(15_000) {
            while (label !in labels()) delay(50)
            true
        } == true, "Missing label: $label")
    }

    private suspend fun labels(): List<String> = withContext(Dispatchers.Main.immediate) {
        nodes().flatMap(::ownLabels)
    }

    private suspend fun click(label: String) {
        awaitLabel(label)
        withTimeout(30_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                val leaf = nodes().firstOrNull { label in ownLabels(it) } ?: return@withContext false
                val button = generateSequence(leaf) { it.parent }.firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null
                } ?: return@withContext false
                if (button.config.getOrNull(SemanticsProperties.Disabled) != null) return@withContext false
                check(button.config[SemanticsActions.OnClick].action!!.invoke()) { "Click refused: $label" }
                true
            }) delay(50)
        }
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
