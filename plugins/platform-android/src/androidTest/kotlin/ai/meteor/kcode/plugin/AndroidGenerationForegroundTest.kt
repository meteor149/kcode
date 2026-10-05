package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.plugin.api.AndroidForegroundExecutionHost
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.notifications.LocalizedAndroidGenerationForegroundPlugin
import ai.meteor.kcode.plugin.notifications.generationForegroundConfig
import ai.meteor.kcode.plugin.localization.LocalizationFeaturePlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import kotlinx.serialization.json.Json
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGenerationForegroundTest {
    @Test(timeout = 60_000)
    fun actualApkPolicyReleasesLeasesRebindsGenerationAndKeepsOverlappingRuntimeAlive(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "generation-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "generation.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): Context = context }
        }
        val host = RecordingForegroundHost()
        val roots = mutableListOf<AndroidPluginHostInputs>()
        val runtimes = mutableListOf<KcodePluginRuntime>()
        suspend fun create(id: String): Pair<KcodePluginRuntime, () -> ChatGenerationRunner> {
            lateinit var runner: ChatGenerationRunner
            val trusted = File(directory, id).apply { mkdirs() }
            val privateApk = File(trusted, "generation.apk")
            apk.copyTo(privateApk)
            check(privateApk.setReadOnly())
            val inputs = AndroidPluginHostInputs(activity, host).also { roots += it }
            val capture = kcodePlugin(PluginDescriptor("test.generation", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-generation", inject = dependencies(KcodeGeneration.Key)) { ctx, _ ->
                    runner = ctx.require(KcodeGeneration.Key).runner
                }, Unit)
            val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                hostInputs = inputs, featurePlugins = listOf(capture),
                settingsStore = object : AppSettingsStore {
                    override val protection = SettingsProtection.Transient
                    override suspend fun load() = LegacySettings(language = "en")
                    override suspend fun save(settings: StoredAppSettings) = Unit
                },
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                    AndroidDynamicPluginController(ctx, context, loader, inventory, trusted)
                },
            )).also { runtimes += it }
            val digest = packageFileSha256(apk)
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.generation", version = "apk", artifactPath = privateApk.path, sha256 = digest,
                entryClass = GenerationProviderPlugin::class.java.name, packageName = instrumentation.context.packageName,
            ))
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "feature.localization", version = "native-private-words", artifactPath = privateApk.path,
                sha256 = digest, entryClass = LocalizationFeaturePlugin::class.java.name,
                packageName = instrumentation.context.packageName,
                config = Json.parseToJsonElement("""{"translations":{"en":{"generation_notification_channel":"Test model responses","generation_notification_title":"Generation $id","generation_notification_text":"Owned response"}}}"""),
            ))
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "generation.foreground", version = "apk", artifactPath = privateApk.path, sha256 = digest,
                entryClass = LocalizedAndroidGenerationForegroundPlugin::class.java.name,
                packageName = instrumentation.context.packageName,
                config = Unit,
            ))
            return runtime to { runner }
        }
        suspend fun awaitLeases(count: Int) = withTimeout(5_000) { while (host.active.size != count) delay(10) }
        try {
            val (first, firstRunner) = create("first")
            val initial = firstRunner()
            assertEquals("ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner", initial.javaClass.name)
            assertNotSame(ChatGenerationRunner::class.java.classLoader, initial.javaClass.classLoader)
            assertNotSame(GenerationProviderPlugin::class.java.classLoader, initial.javaClass.classLoader)
            val gate = CompletableDeferred<Unit>()
            val response = withContext(Dispatchers.Main.immediate) { initial.launch { gate.await() } }
            val concurrent = withContext(Dispatchers.Main.immediate) { initial.launch { awaitCancellation() } }
            awaitLeases(1)
            assertEquals(2, initial.activeTasks.value)
            gate.complete(Unit)
            response.join()
            assertEquals(1, initial.activeTasks.value)
            assertEquals(1, host.acquired.get())
            first.pluginManager.setEnabled("generation.foreground", false)
            awaitLeases(0)
            assertTrue(concurrent.isActive)
            first.pluginManager.setEnabled("generation.foreground", true)
            awaitLeases(1)
            first.pluginManager.replace(DynamicPluginSpec(
                id = "provider.generation", version = "apk-next", artifactPath = File(directory, "first/generation.apk").path,
                sha256 = packageFileSha256(apk),
                entryClass = GenerationProviderPlugin::class.java.name, packageName = instrumentation.context.packageName,
            ))
            concurrent.join()
            assertTrue(concurrent.isCancelled)
            awaitLeases(0)
            assertFailsWith<IllegalStateException> { initial.launch { error("retired runner executed") } }
            val firstTask = withContext(Dispatchers.Main.immediate) { firstRunner().launch { awaitCancellation() } }
            awaitLeases(1)
            val (second, secondRunner) = create("second")
            val secondTask = withContext(Dispatchers.Main.immediate) { secondRunner().launch { awaitCancellation() } }
            awaitLeases(2)
            first.close()
            firstTask.join()
            awaitLeases(1)
            assertTrue(secondTask.isActive)
            second.close()
            secondTask.join()
            awaitLeases(0)
            assertEquals(host.acquired.get(), host.released.get())
            assertTrue(host.notifications.all { it.extras.getString(Notification.EXTRA_TITLE)?.startsWith("Generation ") == true })
        } finally {
            withContext(NonCancellable) { runtimes.asReversed().forEach { it.close() } }
            apk.setWritable(true)
            directory.deleteRecursively()
        }
        roots.forEach { inputs ->
            assertFailsWith<IllegalStateException> { inputs.activity() }
            assertFailsWith<IllegalStateException> {
                inputs.foregroundExecution().acquire(Notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        }
    }

    @Test(timeout = 30_000)
    fun nativeHostCoordinatesIndependentLeasesAndRejectsUndeclaredTypes(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val events = mutableListOf<String>()
        var rejectStart = false
        val leases = mutableListOf<AutoCloseable>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName {
                check(!rejectStart) { "Foreground acquisition denied" }
                events += "acquire"
                return requireNotNull(service.component)
            }
            override fun startService(service: Intent): ComponentName {
                events += "update"
                return requireNotNull(service.component)
            }
            override fun stopService(service: Intent): Boolean { events += "stop"; return true }
        }
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): Context = context }
        }
        val inputs = AndroidPluginHostInputs(activity)
        val host = inputs.foregroundExecution()
        try {
            val first = host.acquire(Notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC).also { leases += it }
            val second = host.acquire(Notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC).also { leases += it }
            rejectStart = true
            assertFailsWith<IllegalStateException> { host.acquire(Notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            rejectStart = false
            first.close()
            first.close()
            assertEquals(listOf("acquire", "acquire", "update"), events)
            second.close()
            assertEquals(listOf("acquire", "acquire", "update", "stop"), events)
            assertFailsWith<IllegalArgumentException> { host.acquire(Notification(), 0) }
            inputs.close()
            assertFailsWith<IllegalStateException> { host.acquire(Notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        } finally {
            leases.asReversed().forEach { it.close() }
            inputs.close()
        }
    }

    @Test(timeout = 30_000)
    fun realServiceStaysForegroundUntilItsLastNativeLeaseCloses(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "native-foreground-test-${System.nanoTime()}"
        manager.createNotificationChannel(NotificationChannel(channel, "Foreground host test", NotificationManager.IMPORTANCE_LOW))
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): Context = context }
        }
        val inputs = AndroidPluginHostInputs(activity)
        val leases = mutableListOf<AutoCloseable>()
        val automation = instrumentation.uiAutomation
        val packageName = context.packageName
        require(packageName == "ai.meteor.kcode.plugin.platform.android.test")
        suspend fun shell(command: String) = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .bufferedReader().use { it.readText().trim() }
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            assertEquals("", shell("pm grant $packageName android.permission.POST_NOTIFICATIONS"))
        }
        assertEquals("", shell("cmd appops set $packageName POST_NOTIFICATION allow"))
        automation.adoptShellPermissionIdentity(
            "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND",
            "android.permission.POST_NOTIFICATIONS",
        )
        suspend fun awaitNotification(title: String) = withTimeout(5_000) {
            while (manager.activeNotifications.none { notification ->
                notification.id == 1001 && notification.notification.extras.getString(Notification.EXTRA_TITLE) == title &&
                    notification.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            }) delay(10)
        }
        fun notification(title: String) = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build()
        try {
            assertTrue(manager.areNotificationsEnabled())
            val first = inputs.foregroundExecution().acquire(notification("First native lease"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                .also { leases += it }
            awaitNotification("First native lease")
            val second = inputs.foregroundExecution().acquire(notification("Second native lease"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                .also { leases += it }
            awaitNotification("Second native lease")
            first.close()
            awaitNotification("Second native lease")
            second.close()
            withTimeout(5_000) { while (manager.activeNotifications.any { it.id == 1001 }) delay(10) }
        } finally {
            try {
                leases.asReversed().forEach { it.close() }
                inputs.close()
                manager.deleteNotificationChannel(channel)
                // AGP uninstalls this standalone test APK; revoking here can kill instrumentation.
            } finally { automation.dropShellPermissionIdentity() }
        }
    }

    private class RecordingForegroundHost : AndroidForegroundExecutionHost {
        val active = ConcurrentHashMap.newKeySet<AutoCloseable>()
        val notifications = java.util.concurrent.CopyOnWriteArrayList<Notification>()
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        override fun acquire(notification: Notification, serviceType: Int): AutoCloseable {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, serviceType)
            val closed = AtomicBoolean(false)
            lateinit var lease: AutoCloseable
            lease = AutoCloseable {
                if (closed.compareAndSet(false, true)) { active.remove(lease); released.incrementAndGet() }
            }
            active += lease
            notifications += notification
            acquired.incrementAndGet()
            return lease
        }
    }
}
