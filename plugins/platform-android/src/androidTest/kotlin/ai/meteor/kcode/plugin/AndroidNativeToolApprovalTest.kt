package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ConfirmationDialogHost
import ai.meteor.kcode.plugin.api.ConfirmationDialogRequest
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeToolApprovals
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import android.app.Activity
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import ai.meteor.kcode.plugin.localization.LocalizationProviderPlugin
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNativeToolApprovalTest {
    @Test(timeout = 60_000)
    fun actualArtifactOwnsApprovalAndWithdrawalJoinsDialogCleanup(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-approvals-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "approvals.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): Context = context }
        }
        val store = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            var value = StoredAppSettings(toolPermissionMode = ToolPermissionMode.Deny.code)
            override suspend fun load() = value
            override suspend fun save(settings: StoredAppSettings) { value = settings }
        }
        val dialogs = RecordingDialogs()
        val root = AndroidPluginHostInputs(activity, null, dialogs)
        val staleHost = requireNotNull(root.confirmationDialogs())
        lateinit var policy: InteractionPolicy
        lateinit var approver: ToolCallApprover
        val capture = kcodePlugin(descriptor("test.approvals"), plugin<Unit>(
            name = "capture-native-approvals", inject = dependencies(KcodeInteraction.Key, KcodeToolApprovals.Key),
        ) { ctx, _ ->
            policy = ctx.require(KcodeInteraction.Key).policy
            approver = ctx.require(KcodeToolApprovals.Key).approver
        }, Unit)
        val configuration = toolApprovalConfig("Allow %1\$s?", "%1\$s|%2\$s", "approve", "reject")
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { error("obsolete host approval") }),
            hostInputs = root, settingsStore = store, featurePlugins = listOf(capture),
            profile = KcodePluginProfile(overrides = listOf(kcodePlugin(
                descriptor("provider.interaction.platform"), HostModeToolInteractionPlugin(), { ToolPermissionMode.Ask },
            ))),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        fun spec(config: Any? = configuration) = DynamicPluginSpec(
            id = "provider.tool-approvals.native", version = "test", artifactPath = artifact.path,
            sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
            entryClass = if (config == Unit) LocalizedNativeToolApprovalPlugin::class.java.name else NativeToolApprovalPlugin::class.java.name, config = config,
        )
        try {
            runtime.pluginManager.install(spec())
            assertNotSame(NativeToolApprovalPlugin::class.java.classLoader, approver.javaClass.classLoader)
            assertEquals(ToolPermissionMode.Ask, policy.permissionModeProvider())
            val request = ToolApprovalRequest("tool%1\$s", "x".repeat(9_000), "d".repeat(3_000))
            assertTrue(policy.approver.approve(request))
            assertEquals(ConfirmationDialogRequest("Allow tool%1\$s?", "d".repeat(2_048) + "|" + "x".repeat(8_192), "approve", "reject"), dialogs.last)
            dialogs.answer = false
            assertFalse(policy.approver.approve(ToolApprovalRequest("fallback", "input", "")))
            assertEquals("fallback|input", dialogs.last?.message)
            val dictionary = artifact
            val localized = DynamicPluginSpec(
                id = "provider.localization.default", version = "private-native-text", artifactPath = dictionary.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(dictionary.readBytes()).joinToString("") { "%02x".format(it) },
                entryClass = LocalizationProviderPlugin::class.java.name,
                packageName = instrumentation.context.packageName,
                config = Json.parseToJsonElement("""{"defaultLanguage":"en","translations":{"en":{"tool_confirmation_title":"Private %1${'$'}s","tool_confirmation_message":"%1${'$'}s|%2${'$'}s","tool_confirmation_allow":"Private allow","tool_confirmation_deny":"Private deny"}}}"""),
            )
            runtime.pluginManager.replace(localized)
            runtime.pluginManager.replace(spec(Unit))
            assertFalse(policy.approver.approve(ToolApprovalRequest("native", "input", "purpose")))
            assertEquals(ConfirmationDialogRequest("Private native", "purpose|input", "Private allow", "Private deny"), dialogs.last)
            val translatedApprover = approver
            runtime.pluginManager.setEnabled("provider.localization.default", false)
            assertFailsWith<IllegalStateException> { translatedApprover.approve(request) }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.single { it.id == "provider.tool-approvals.native" }.state)
            runtime.pluginManager.setEnabled("provider.localization.default", true)
            assertFalse(policy.approver.approve(ToolApprovalRequest("restored", "input", "purpose")))
            assertEquals("Private restored", dialogs.last?.title)
            runtime.pluginManager.replace(spec())
            val previous = policy
            val previousApprover = approver
            dialogs.wait = true
            val call = async { previous.approver.approve(request) }
            withTimeout(5_000) { dialogs.entered.await() }
            val rejected = async {
                runCatching { runtime.pluginManager.replace(spec(toolApprovalConfig("%q", "%1\$s", "yes", "no"))) }.exceptionOrNull()
            }
            assertIs<IllegalStateException>(withTimeout(5_000) { rejected.await() })
            assertSame(previous, policy)
            assertSame(previousApprover, approver)
            assertFalse(call.isCompleted)
            assertFalse(dialogs.cleaning.isCompleted, "Invalid deployment config must not close the active dialog")
            val withdrawal = async { runtime.pluginManager.setEnabled("provider.tool-approvals.native", false) }
            withTimeout(5_000) { dialogs.cleaning.await() }
            assertFalse(withdrawal.isCompleted)
            dialogs.release.complete(Unit)
            withTimeout(5_000) { withdrawal.await(); call.join() }
            assertTrue(call.isCancelled)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.interaction.platform" }.state)
            assertFailsWith<IllegalStateException> { previous.approver.approve(request) }
            assertFailsWith<IllegalStateException> { previousApprover.approve(request) }
            runtime.pluginManager.setEnabled("provider.tool-approvals.native", true)
            dialogs.wait = false
            assertFalse(policy.approver.approve(request))
            // Pure configuration rejection preserves the currently active provider.
            assertFailsWith<Exception> { runtime.pluginManager.replace(spec(toolApprovalConfig("%q", "%1\$s", "yes", "no"))) }
            assertFalse(policy.approver.approve(request))
            runtime.pluginManager.replace(spec().copy(
                id = "provider.interaction.platform", entryClass = SettingsToolInteractionPlugin::class.java.name, config = Unit,
            ))
            assertNotSame(SettingsToolInteractionPlugin::class.java.classLoader, policy.permissionModeProvider.javaClass.classLoader)
            assertEquals(ToolPermissionMode.Deny, policy.permissionModeProvider())
            store.save(StoredAppSettings(toolPermissionMode = "unknown-mode"))
            assertEquals(ToolPermissionMode.Ask, policy.permissionModeProvider())
            store.save(StoredAppSettings(toolPermissionMode = ToolPermissionMode.Bypass.code))
            assertEquals(ToolPermissionMode.Bypass, policy.permissionModeProvider())
            val oldPolicy = policy
            runtime.pluginManager.setEnabled("provider.settings.platform", false)
            assertFailsWith<IllegalStateException> { oldPolicy.permissionModeProvider() }
            runtime.pluginManager.setEnabled("provider.settings.platform", true)
            assertEquals(ToolPermissionMode.Bypass, policy.permissionModeProvider())
            val restored = approver
            runtime.pluginManager.uninstall("provider.tool-approvals.native")
            assertFailsWith<IllegalStateException> { restored.approve(request) }
        } finally {
            dialogs.release.complete(Unit)
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
        assertFailsWith<IllegalStateException> { staleHost.confirm(ConfirmationDialogRequest("t", "m", "y", "n")) }
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())

    private class RecordingDialogs : ConfirmationDialogHost {
        var last: ConfirmationDialogRequest? = null
        var answer = true
        var wait = false
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun confirm(request: ConfirmationDialogRequest): Boolean {
            last = request
            if (!wait) return answer
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        }
    }
}
