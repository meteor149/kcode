package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.localization.LocalizationProviderPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.Json

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.notifications.AndroidNativeNotificationsPlugin
import ai.meteor.kcode.plugin.notifications.LocalizedAndroidNativeNotificationsPlugin
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeScheduledTaskNotifications
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNativeNotificationsTest {
    @Test(timeout = 60_000)
    fun privateApkPostsNativeNotificationAndCancelsOnlyItsGeneration(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        require(packageName == "ai.meteor.kcode.plugin.platform.android.test")
        val previouslyGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        suspend fun permission(action: String) = withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm $action $packageName android.permission.POST_NOTIFICATIONS",
            )).bufferedReader().use { it.readText() }
        }
        if (!previouslyGranted) assertEquals("", permission("grant").trim())
        withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd appops set $packageName POST_NOTIFICATION allow",
            )).bufferedReader().use { assertEquals("", it.readText().trim()) }
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            assertTrue(manager.areNotificationsEnabled())
            val directory = File(context.cacheDir, "notifications-apk-${System.nanoTime()}").apply { mkdirs() }
            val apk = File(directory, "notifications.apk")
            File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
            check(apk.setReadOnly())
            val title = "kcode-plugin-notification-${System.nanoTime()}"
            lateinit var current: ScheduledTaskPlatformHost
            val capture = kcodePlugin(PluginDescriptor("test.capture-notifications", "test", "test", emptySet()), plugin<Unit>(
                name = "capture-native-notifications", inject = dependencies(KcodeScheduledTaskNotifications.Key),
            ) { ctx, _ -> current = ctx.require(KcodeScheduledTaskNotifications.Key).host }, Unit)
            val activity = withContext(Dispatchers.Main.immediate) {
                object : Activity() {
                    override fun getApplicationContext(): android.content.Context = context.applicationContext
                }
            }
            val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                hostInputs = AndroidPluginHostInputs(activity),
                settingsStore = object : AppSettingsStore {
                    override val protection = SettingsProtection.Transient
                    override suspend fun load() = StoredAppSettings(language = "en")
                    override suspend fun save(settings: StoredAppSettings) = Unit
                },
                featurePlugins = listOf(capture),
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                    AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
                },
            ))
            var overlapping: KcodePluginRuntime? = null
            try {
                runtime.pluginManager.replace(DynamicPluginSpec(
                    id = "provider.localization.default", version = "native-private-words", artifactPath = apk.path,
                    sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                    entryClass = LocalizationProviderPlugin::class.java.name, packageName = instrumentation.context.packageName,
                    config = Json.parseToJsonElement("""{"translations":{"en":{"scheduled_task_notification_channel":"Private channel"}}}"""),
                ))
                val spec = DynamicPluginSpec(
                    id = "provider.notifications.platform", version = "test", entryClass = LocalizedAndroidNativeNotificationsPlugin::class.java.name,
                    artifactPath = apk.path, config = Unit,
                    sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                    packageName = instrumentation.context.packageName,
                )
                runtime.pluginManager.replace(spec)
                assertTrue(current.javaClass.classLoader !== ScheduledTaskPlatformHost::class.java.classLoader)
                assertFalse(current.isAppInForeground())
                val previous = current
                previous.showTriggeredNotification(title, "private APK notification")
                val posted = withTimeout(5_000) {
                    while (true) {
                        val found = manager.activeNotifications.firstOrNull { it.notification.extras.getString("android.title") == title }
                        if (found != null) return@withTimeout found
                        delay(25)
                    }
                    error("Unreachable")
                }
                assertEquals("private APK notification", posted.notification.extras.getString("android.text"))
                assertEquals("Private channel", manager.getNotificationChannel(posted.notification.channelId).name.toString())
                lateinit var shadow: ScheduledTaskPlatformHost
                val shadowCapture = kcodePlugin(PluginDescriptor("test.capture-shadow", "test", "test", emptySet()), plugin<Unit>(
                    name = "capture-shadow", inject = dependencies(KcodeScheduledTaskNotifications.Key),
                ) { ctx, _ -> shadow = ctx.require(KcodeScheduledTaskNotifications.Key).host }, Unit)
                val second = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                    interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                hostInputs = AndroidPluginHostInputs(activity),
                    profile = KcodePluginProfile(includeDefaults = false), featurePlugins = listOf(shadowCapture),
                    dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                        AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
                    },
                ))
                overlapping = second
                second.pluginManager.install(spec.copy(id = "fixture.notifications.shadow",
                    entryClass = AndroidNativeNotificationsPlugin::class.java.name, config = "Fixture channel"))
                shadow.showTriggeredNotification("shadow-$title", "overlapping generation")
                val shadowPosted = withTimeout(5_000) {
                    while (true) {
                        val found = manager.activeNotifications.firstOrNull { it.notification.extras.getString("android.title") == "shadow-$title" }
                        if (found != null) return@withTimeout found
                        delay(25)
                    }
                    error("Unreachable")
                }
                assertEquals(posted.id, shadowPosted.id)
                assertTrue(posted.tag != shadowPosted.tag)
                runtime.pluginManager.setEnabled("provider.notifications.platform", false)
                withTimeout(5_000) {
                    while (manager.activeNotifications.any { it.tag == posted.tag }) delay(25)
                }
                assertTrue(manager.activeNotifications.any { it.tag == shadowPosted.tag })
                assertFailsWith<IllegalStateException> { previous.showTriggeredNotification(title, "stale") }
                assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "consumer.schedules.application" }.state)
                runtime.pluginManager.setEnabled("provider.notifications.platform", true)
                current.showTriggeredNotification(title, "new generation")
                val replacement = withTimeout(5_000) {
                    while (true) {
                        val found = manager.activeNotifications.firstOrNull { it.notification.extras.getString("android.title") == title }
                        if (found != null) return@withTimeout found
                        delay(25)
                    }
                    error("Unreachable")
                }
                assertTrue(posted.tag != replacement.tag)
                val beforeUninstall = current
                runtime.pluginManager.uninstall("provider.notifications.platform")
                withTimeout(5_000) {
                    while (manager.activeNotifications.any { it.tag == replacement.tag }) delay(25)
                }
                assertTrue(manager.activeNotifications.any { it.tag == shadowPosted.tag })
                assertFailsWith<IllegalStateException> { beforeUninstall.isAppInForeground() }
            } finally {
                overlapping?.close()
                runtime.close()
                manager.activeNotifications.filter { it.notification.extras.getString("android.title") in setOf(title, "shadow-$title") }
                    .forEach { manager.cancel(it.tag, it.id) }
                apk.setWritable(true)
                directory.deleteRecursively()
                // AGP uninstalls this standalone test APK; revoking here can kill instrumentation.
            }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }
}
