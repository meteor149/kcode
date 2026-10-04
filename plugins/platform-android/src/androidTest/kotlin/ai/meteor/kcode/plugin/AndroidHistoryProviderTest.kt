package ai.meteor.kcode.plugin

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.history.FactoryHistoryProviderPlugin
import ai.meteor.kcode.plugin.history.androidHistoryRepositoryFactory
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Instrumentation
import android.content.ContextWrapper
import android.database.SQLException
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
class AndroidHistoryProviderTest {
    @Test(timeout = 60_000)
    fun isolatedApkOwnsRoomAndReopensDurableDataAfterWithdrawal(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "history-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "history.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var repository: ConversationHistoryRepository
        val capture = kcodePlugin(PluginDescriptor("test.history", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-history", inject = dependencies(KcodeHistory.Key),
        ) { ctx, _ -> repository = ctx.require(KcodeHistory.Key).repository }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            profile = KcodePluginProfile(disabled = setOf("provider.history.platform")),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.history", version = "test", entryClass = AndroidFixtureHistory::class.java.name,
                artifactPath = apk.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                packageName = instrumentation.context.packageName, config = File(directory, "history.db").absolutePath,
            ))
            val previous = repository
            assertTrue(previous.javaClass.classLoader !== FactoryHistoryProviderPlugin::class.java.classLoader)
            val delegate = previous.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(previous)
            val database = delegate.javaClass.getDeclaredField("database").apply { isAccessible = true }.get(delegate)
            assertTrue(delegate.javaClass.classLoader !== FactoryHistoryProviderPlugin::class.java.classLoader)
            assertTrue(database.javaClass.classLoader !== FactoryHistoryProviderPlugin::class.java.classLoader)
            previous.appendMessage(1, "APK", 1, "User", "persistent")
            assertTrue(previous.loadAll().single().javaClass === StoredConversation::class.java)
            runtime.pluginManager.setEnabled("fixture.history", false)
            assertFailsWith<IllegalStateException> { previous.loadAll() }
            runtime.pluginManager.setEnabled("fixture.history", true)
            assertEquals("persistent", repository.loadAll().single().messages.single().content)
            val replacement = repository
            runtime.pluginManager.uninstall("fixture.history")
            assertFailsWith<IllegalStateException> { replacement.loadAll() }
        } finally { runtime.close(); apk.setWritable(true); directory.deleteRecursively() }
    }

    @Test(timeout = 60_000)
    fun nativeFactoryClosesItsDatabaseAndPreservesDataAcrossReallocation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "history-native-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        val factory = androidHistoryRepositoryFactory(isolated)
        try {
            val first = factory.create()
            try { first.repository.appendMessage(1, "native", 1, "User", "persistent") } finally { first.close() }
            val closed = assertFailsWith<SQLException> { first.repository.loadAll() }
            assertTrue(closed.message.orEmpty().contains("Connection pool is closed"))
            val second = factory.create()
            try { assertEquals("persistent", second.repository.loadAll().single().messages.single().content) }
            finally { second.close() }
        } finally { directory.deleteRecursively() }
    }
}

class AndroidFixtureHistory : Plugin<String> {
    override val name = "fixture-history-room"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        // Obtain the test harness's platform context without sharing the private Room implementation.
        val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry", true, HistoryRepositoryFactory::class.java.classLoader)
        val instrumentation = registry.getMethod("getInstrumentation").invoke(null) as Instrumentation
        val isolated = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getDatabasePath(name: String) = File(config)
        }
        FactoryHistoryProviderPlugin.apply(ctx, androidHistoryRepositoryFactory(isolated), effect)
    }
}
