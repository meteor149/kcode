package ai.meteor.kcode.plugin

import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin
import ai.meteor.kcode.plugin.api.KcodeConversationImageSaving
import androidx.compose.ui.graphics.ImageBitmap
import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.notifications.DesktopNativeNotificationsPlugin
import ai.meteor.kcode.plugin.api.KcodeScheduledTaskNotifications
import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.cordis.dependencies
import org.cordis.plugin

class HostInputsCompositionTest {
    @Test
    fun actualNativeImageJarRestoresAndRevokesItsSaver(): Unit = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("native-image-inputs").toFile()
        val artifact = java.io.File(directory, "images.jar")
        java.io.File(ConversationExportFeaturePlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        var root = DesktopPluginHostInputs { null }
        lateinit var saver: ConversationImageSaver
        val capture = kcodePlugin(PluginDescriptor("test.image-input", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-image-input", inject = dependencies(KcodeConversationImageSaving.Key)) { ctx, _ ->
                saver = ctx.require(KcodeConversationImageSaving.Key).saver
            }, Unit)
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), hostInputs = root,
            featurePlugins = listOf(capture), pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        )
        val bitmap = ImageBitmap(1, 1)
        var runtime = KcodePluginRuntime.create(configuration())
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "feature.conversation-export", version = "external", artifactPath = artifact.path,
                sha256 = digest, entryClass = ConversationExportFeaturePlugin::class.java.name,
            ))
            kotlin.test.assertTrue(saver.javaClass.classLoader !== ConversationImageSaver::class.java.classLoader)
            assertEquals(ImageSaveResult.Unsupported, saver.share(bitmap, "test.png"))
            val retired = saver
            runtime.pluginManager.setEnabled("feature.conversation-export", false)
            assertFailsWith<IllegalStateException> { retired.share(bitmap, "test.png") }
            runtime.pluginManager.setEnabled("feature.conversation-export", true)
            assertEquals(ImageSaveResult.Unsupported, saver.share(bitmap, "test.png"))
            runtime.close()
            root = DesktopPluginHostInputs { null }
            runtime = KcodePluginRuntime.create(configuration())
            assertEquals(ImageSaveResult.Unsupported, saver.share(bitmap, "test.png"))
            val beforeUninstall = saver
            runtime.pluginManager.uninstall("feature.conversation-export")
            assertFailsWith<IllegalStateException> { beforeUninstall.share(bitmap, "test.png") }
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun actualNativeNotificationJarUsesHostLeaseAfterPersistedRestart() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("native-notifications-inputs").toFile()
        val artifact = java.io.File(directory, "notifications.jar")
        val packaged = java.io.File(DesktopNativeNotificationsPlugin::class.java.protectionDomain.codeSource.location.toURI())
        check(packaged.isFile) { "Native module must be packaged as its real JAR" }
        packaged.copyTo(artifact)
        check(artifact.setReadOnly())
        var reads = 0
        var root = DesktopPluginHostInputs { reads += 1; null }
        lateinit var notifications: ScheduledTaskPlatformHost
        val capture = kcodePlugin(PluginDescriptor("test.notifications-input", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-notifications-input", inject = dependencies(KcodeScheduledTaskNotifications.Key)) { ctx, _ ->
                notifications = ctx.require(KcodeScheduledTaskNotifications.Key).host
            }, Unit)
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), hostInputs = root,
            featurePlugins = listOf(capture), pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(configuration())
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.notifications.platform", version = "external", artifactPath = artifact.path,
                sha256 = digest, entryClass = DesktopNativeNotificationsPlugin::class.java.name,
            ))
            kotlin.test.assertTrue(notifications.javaClass.classLoader !== ScheduledTaskPlatformHost::class.java.classLoader)
            kotlin.test.assertFalse(notifications.isAppInForeground())
            assertEquals(1, reads)
            val retired = notifications
            runtime.pluginManager.setEnabled("provider.notifications.platform", false)
            assertFailsWith<IllegalStateException> { retired.isAppInForeground() }
            assertEquals(1, reads)
            runtime.pluginManager.setEnabled("provider.notifications.platform", true)
            kotlin.test.assertFalse(notifications.isAppInForeground())
            runtime.close()
            assertFailsWith<IllegalStateException> { root.applicationWindow() }
            root = DesktopPluginHostInputs { reads += 1; null }
            runtime = KcodePluginRuntime.create(configuration())
            kotlin.test.assertFalse(notifications.isAppInForeground())
            assertEquals(3, reads)
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    @Test
    fun inputLeaseStaysUsableDuringProviderCleanupAndRevokesBeforeRootRelease() = runBlocking {
        val phases = mutableListOf<String>()
        val root = DesktopPluginHostInputs { phases += "read-window"; null }
        lateinit var lease: DesktopPluginHostInputs
        val mount = kcodePlugin(PluginDescriptor("test.host-owner", "test", "test", emptySet()),
            plugin<Unit>(name = "host-owner") { ctx, _ ->
                lease = PluginHostInputs.current(ctx, this) as DesktopPluginHostInputs
                collect { lease.applicationWindow(); phases += "provider-close" }
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            hostInputs = root, bundle = listOf(mount),
        ))
        runtime.close()
        assertEquals(listOf("read-window", "provider-close"), phases)
        assertFailsWith<IllegalStateException> { lease.applicationWindow() }
        assertFailsWith<IllegalStateException> { root.applicationWindow() }
        runtime.close()
    }

    @Test
    fun creationFailureReleasesInputsBeforeAndAfterProductAdmission(): Unit = runBlocking {
        fun configuration(root: DesktopPluginHostInputs) = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), hostInputs = root,
        )
        val early = DesktopPluginHostInputs { null }
        assertFailsWith<IllegalArgumentException> {
            KcodePluginRuntime.create(configuration(early).copy(profile = KcodePluginProfile(disabled = setOf("unknown"))))
        }
        assertFailsWith<IllegalStateException> { early.applicationWindow() }
        val late = DesktopPluginHostInputs { null }
        lateinit var lease: DesktopPluginHostInputs
        val mount = kcodePlugin(PluginDescriptor("test.failed-host-owner", "test", "test", emptySet()),
            plugin<Unit>(name = "failed-host-owner") { ctx, _ ->
                lease = PluginHostInputs.current(ctx, this) as DesktopPluginHostInputs
            }, Unit)
        assertFailsWith<IllegalStateException> {
            KcodePluginRuntime.create(configuration(late).copy(
                bundle = listOf(mount),
                dynamicPluginControllerFactory = DynamicPluginControllerFactory { _, _, _ -> error("controller initialization failed") },
            ))
        }
        assertFailsWith<IllegalStateException> { lease.applicationWindow() }
        assertFailsWith<IllegalStateException> { late.applicationWindow() }
    }
}
