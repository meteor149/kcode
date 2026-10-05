package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.plugin.settingsstorage.FactorySettingsProviderPlugin
import ai.meteor.kcode.plugin.settingsstorage.androidSettingsStoreFactory
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.settings.native.MmkvSettingsLease
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Instrumentation
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
class AndroidSettingsStorageTest {
    @Test(timeout = 60_000)
    fun overlappingEncryptedNativeResourcesSurviveOldReleaseAndReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "settings-native-${System.nanoTime()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
        }
        val factory = androidSettingsStoreFactory(scoped)
        try {
            val first = factory.create()
            val second = factory.create()
            val settings = LegacySettings(provider = "custom", modelApiKeys = mapOf("custom" to "fixture"), language = "en")
            try {
                first.store.save(settings)
                assertEquals(settings, second.store.load())
                first.close()
                first.close()
                assertFailsWith<IllegalStateException> { first.store.load() }
                assertEquals(settings, second.store.load())
                second.store.save(settings.copy(language = "zh"))
            } finally { first.close(); second.close() }
            assertFailsWith<IllegalStateException> { second.store.load() }
            val reopened = factory.create()
            try {
                assertEquals(settings.copy(language = "zh"), reopened.store.load())
                assertEquals(SettingsProtection.AndroidKeystore, reopened.store.protection)
            } finally { reopened.close() }
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 60_000)
    fun isolatedApkOwnsTheSettingsCodecAndBorrowsOnlyTheHostMmkvLease(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "settings-storage-apk-${System.nanoTime()}").apply { mkdirs() }
        val storageDirectory = File(directory, "storage").apply { mkdirs() }
        val apk = File(directory, "settings.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var store: AppSettingsStore
        val capture = kcodePlugin(descriptor("test.capture"), plugin<Unit>(
            name = "capture-settings", inject = dependencies(KcodeSettings.Key),
        ) { ctx, _ -> store = ctx.require(KcodeSettings.Key).store }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            profile = KcodePluginProfile(disabled = setOf("provider.settings.platform")), featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.settings-storage", version = "test", entryClass = AndroidFixtureSettingsStorage::class.java.name,
                artifactPath = apk.path,
                sha256 = packageFileSha256(apk),
                packageName = instrumentation.context.packageName, config = storageDirectory.path,
            ))
            val previous = store
            val delegate = previous.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(previous)
            assertTrue(delegate.javaClass.classLoader !== FactorySettingsProviderPlugin::class.java.classLoader)
            val lease = delegate.javaClass.getDeclaredField("lease").apply { isAccessible = true }.get(delegate)
            assertTrue(lease.javaClass === MmkvSettingsLease::class.java)
            val settings = LegacySettings(provider = "custom", modelApiKeys = mapOf("custom" to "fixture"), language = "en")
            previous.save(settings)
            val restored = previous.load()
            assertTrue(restored.javaClass === StoredAppSettings::class.java)
            assertEquals(settings, restored)
            runtime.pluginManager.setEnabled("fixture.settings-storage", false)
            assertFailsWith<IllegalStateException> { previous.load() }
            runtime.pluginManager.setEnabled("fixture.settings-storage", true)
            assertEquals(settings, store.load())
            val replacement = store
            runtime.pluginManager.uninstall("fixture.settings-storage")
            assertFailsWith<IllegalStateException> { replacement.load() }
        } finally { runtime.close(); apk.setWritable(true); directory.deleteRecursively() }
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
}

class AndroidFixtureSettingsStorage : Plugin<String> {
    override val name = "fixture-settings-storage"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry", true, SettingsStoreFactory::class.java.classLoader)
        val instrumentation = registry.getMethod("getInstrumentation").invoke(null) as Instrumentation
        val scoped = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = File(config)
        }
        FactorySettingsProviderPlugin.apply(ctx, androidSettingsStoreFactory(scoped), effect)
    }
}
