package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ConversationSessionFactory
import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.chat.UnavailableScheduledTasks
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.plugin.api.AndroidPermissionHost
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.notifications.AndroidNotificationPermissionPlugin
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.ui.api.ApplicationEffect
import ai.meteor.kcode.plugin.ui.api.ApplicationEffectRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNotificationPermissionPluginTest {
    @Test(timeout = 60_000)
    fun actualApkRequestsOnlyFromCommittedUiAndWithdrawalJoinsItsWaiter(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "permission-plugin-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "permission.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        val permissions = WaitingPermissions()
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): Context = context }
        }
        val root = AndroidPluginHostInputs(activity, permissions)
        val stalePermissions = requireNotNull(root.permissions())
        lateinit var slots: KcodeUiSlots
        lateinit var sessions: ConversationSessionFactory
        lateinit var history: ConversationHistoryRepository
        lateinit var generation: ChatGenerationRunner
        val capture = kcodePlugin(PluginDescriptor("test.permission", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-permission-ui", inject = dependencies(
                KcodeUiSlots.Key, KcodeSessions.Key, KcodeHistory.Key, KcodeGeneration.Key,
            )) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
                sessions = ctx.require(KcodeSessions.Key).factory
                history = ctx.require(KcodeHistory.Key).repository
                generation = ctx.require(KcodeGeneration.Key).runner
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            hostInputs = root, featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
        val session = sessions.create(scope)
        val request = ApplicationEffectRequest(
            conversationSession = session, configuration = null, chatService = runtime.chatService,
            generationRunner = generation, historyRepository = history,
            goalSessionFactory = UnavailableGoalSessions, scheduledTaskCoordinator = UnavailableScheduledTasks,
            language = AppLanguage.English,
        )
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(1)
                return onFrame(System.nanoTime())
            }
        }
        val recomposer = Recomposer(scope.coroutineContext + clock)
        val composition = withContext(Dispatchers.Main.immediate) { Composition(UnitApplier(), recomposer) }
        val compositor = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        suspend fun render(effect: ApplicationEffect?) {
            withContext(Dispatchers.Main.immediate) {
                composition.setContent { effect?.renderer?.Render(request) }
                Snapshot.sendApplyNotifications()
            }
            withTimeout(5_000) { recomposer.awaitIdle() }
        }
        suspend fun awaitRequests(count: Int) = withTimeout(5_000) { while (permissions.requests.get() != count) delay(10) }
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "permission.policy", version = "test", entryClass = AndroidNotificationPermissionPlugin::class.java.name,
                artifactPath = apk.path, enabled = false, packageName = instrumentation.context.packageName,
                sha256 = packageFileSha256(apk),
            ))
            assertTrue(slots.snapshot().effects.none { it.id == "android.notifications.permission" })
            assertEquals(0, permissions.requests.get())
            runtime.pluginManager.setEnabled("permission.policy", true)
            val first = slots.snapshot().effects.single { it.id == "android.notifications.permission" }
            assertNotSame(UiRenderer::class.java.classLoader, first.renderer.javaClass.classLoader)
            assertEquals(0, permissions.requests.get())
            render(first)
            awaitRequests(1)
            val withdrawal = async { runtime.pluginManager.setEnabled("permission.policy", false) }
            withTimeout(5_000) { permissions.cleanupEntered.await() }
            assertFalse(withdrawal.isCompleted)
            permissions.releaseCleanup.complete(Unit)
            withTimeout(5_000) { withdrawal.await() }
            assertEquals(1, permissions.released.get())
            assertTrue(slots.snapshot().effects.none { it.id == "android.notifications.permission" })
            render(first)
            assertEquals(1, permissions.requests.get())
            runtime.pluginManager.setEnabled("permission.policy", true)
            val next = slots.snapshot().effects.single { it.id == "android.notifications.permission" }
            render(next)
            awaitRequests(2)
            render(null)
            render(next)
            assertEquals(2, permissions.requests.get())
            runtime.pluginManager.uninstall("permission.policy")
            render(next)
            assertEquals(2, permissions.requests.get())
        } finally {
            permissions.releaseCleanup.complete(Unit)
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) { composition.dispose(); recomposer.close() }
                compositor.cancelAndJoin()
                session.close()
                runtime.close()
                scopeJob.cancelAndJoin()
            }
            apk.setWritable(true)
            directory.deleteRecursively()
        }
        assertFailsWith<IllegalStateException> { stalePermissions.isGranted(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private class WaitingPermissions : AndroidPermissionHost {
        val requests = AtomicInteger()
        val released = AtomicInteger()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        override fun isGranted(permission: String): Boolean {
            assertEquals(Manifest.permission.POST_NOTIFICATIONS, permission)
            return false
        }
        override suspend fun request(permission: String): Boolean {
            assertEquals(Manifest.permission.POST_NOTIFICATIONS, permission)
            requests.incrementAndGet()
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await(); released.incrementAndGet() }
            }
        }
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
