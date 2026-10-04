package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.createAndroidKoogChatRuntime
import ai.meteor.kcode.settings.ShellExecutionMode
import android.app.Activity
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSettingsInteractionTest {
    @Test(timeout = 60_000)
    fun nativeFactoryUsesTheSettingsPolicyOnMainAndRebindsIt(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-settings-policy-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        var policy: InteractionPolicy? = null
        val capture = kcodePlugin(descriptor("provider.ui.compose"), plugin<Unit>(
            name = "capture-native-policy", inject = dependencies(KcodeInteraction.Key),
        ) { ctx, _ -> policy = ctx.require(KcodeInteraction.Key).policy }, Unit)
        try {
            withContext(Dispatchers.Main.immediate) {
                val activity = object : Activity() {
                    override fun getApplicationContext(): android.content.Context = isolated
                    override fun getFilesDir() = directory
                }
                val runtime = createAndroidKoogChatRuntime(
                    activity = activity, modeProvider = { ShellExecutionMode.App },
                    permissionModeProvider = { error("obsolete host permission reader") },
                    toolCallApprover = ToolCallApprover { true }, settingsStore = MemorySettings(ToolPermissionMode.Deny),
                    settingsBackedInteraction = true, profile = KcodePluginProfile(overrides = listOf(capture)),
                )
                try {
                    val root = runtime.owner as KcodePluginRuntime
                    val previous = requireNotNull(policy)
                    assertEquals(ToolPermissionMode.Deny, previous.permissionModeProvider())
                    root.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), plugin<MemorySettings>(
                        name = "native-replacement-settings",
                    ) { ctx, config -> KcodeSettings(ctx, config) }, MemorySettings(ToolPermissionMode.Bypass)))
                    assertFailsWith<IllegalStateException> { previous.permissionModeProvider() }
                    assertEquals(ToolPermissionMode.Bypass, requireNotNull(policy).permissionModeProvider())
                } finally { runtime.close() }
            }
        } finally { directory.deleteRecursively() }
    }


    @Test(timeout = 60_000)
    fun isolatedApkPolicyTracksCurrentSettingsAndRevokesPreviousCallbacks(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "settings-interaction-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "interaction.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var policy: InteractionPolicy
        val store = MemorySettings(ToolPermissionMode.Deny)
        val capture = kcodePlugin(descriptor("test.capture"), plugin<Unit>(
            name = "capture-policy", inject = dependencies(KcodeInteraction.Key),
        ) { ctx, _ -> policy = ctx.require(KcodeInteraction.Key).policy }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { error("obsolete approval") }),
            settingsStore = store, featurePlugins = listOf(capture),
            profile = KcodePluginProfile(disabled = setOf("provider.interaction.platform")),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.settings-interaction", version = "test", entryClass = AndroidFixtureSettingsInteraction::class.java.name,
                artifactPath = apk.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                packageName = instrumentation.context.packageName, config = "fixture",
            ))
            assertTrue(policy.permissionModeProvider.javaClass.classLoader !== SettingsInteractionProviderPlugin::class.java.classLoader)
            val mode = policy.permissionModeProvider()
            assertTrue(mode.javaClass === ToolPermissionMode::class.java)
            assertEquals(ToolPermissionMode.Deny, mode)
            store.save(StoredAppSettings(toolPermissionMode = ToolPermissionMode.Bypass.code))
            assertEquals(ToolPermissionMode.Bypass, policy.permissionModeProvider())
            val previous = policy
            val replacement = MemorySettings(ToolPermissionMode.Ask)
            runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), plugin<MemorySettings>(
                name = "fixture-settings",
            ) { ctx, config -> KcodeSettings(ctx, config) }, replacement))
            assertFailsWith<IllegalStateException> { previous.permissionModeProvider() }
            assertEquals(ToolPermissionMode.Ask, policy.permissionModeProvider())
            assertTrue(policy.approver.approve(ToolApprovalRequest("test", "fixture", "approval")))
            val beforeWithdrawal = policy
            runtime.pluginManager.setEnabled("provider.settings.platform", false)
            assertFailsWith<IllegalStateException> { beforeWithdrawal.permissionModeProvider() }
            assertFailsWith<IllegalStateException> { beforeWithdrawal.approver.approve(ToolApprovalRequest("test", "fixture", "approval")) }
            runtime.pluginManager.setEnabled("provider.settings.platform", true)
            assertEquals(ToolPermissionMode.Ask, policy.permissionModeProvider())
            val beforeUninstall = policy
            runtime.pluginManager.uninstall("fixture.settings-interaction")
            assertFailsWith<IllegalStateException> { beforeUninstall.permissionModeProvider() }
        } finally { runtime.close(); apk.setWritable(true); directory.deleteRecursively() }
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private class MemorySettings(mode: ToolPermissionMode) : AppSettingsStore {
        override val protection = SettingsProtection.Transient
        private var value = StoredAppSettings(toolPermissionMode = mode.code)
        override suspend fun load() = value
        override suspend fun save(settings: StoredAppSettings) { value = settings }
    }
}

class AndroidFixtureSettingsInteraction : Plugin<String> {
    override val name = "fixture-settings-interaction"
    override val inject = dependencies(KcodeSettings.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        SettingsInteractionProviderPlugin.apply(ctx, ToolCallApprover { true }, effect)
    }
}
