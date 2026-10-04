package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.AndroidPluginWindow
import ai.meteor.kcode.plugin.api.AndroidPluginWindowContent
import ai.meteor.kcode.plugin.api.AndroidPluginWindowFactory
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.NativeAndroidPluginWindowHost
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPluginWindowHostTest {
    @Test(timeout = 40_000)
    fun realActivitiesAreIsolatedAndCloseWaitsForContentCleanup(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = NativeAndroidPluginWindowHost(instrumentation.targetContext)
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val observedClose = CompletableDeferred<kotlinx.coroutines.Deferred<Unit>>()
        val a = Content("window-a")
        val b = Content("window-b")
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var first: AndroidPluginWindow? = null
        var second: AndroidPluginWindow? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            first = host.open(AndroidPluginWindowFactory { activity -> a.also { it.activity = activity } })
            second = host.open(AndroidPluginWindowFactory { activity -> b.also { it.activity = activity } })
            withTimeout(10_000) { first.show(); second.show(); first.awaitReady(); second.awaitReady() }
            assertNotEquals(a.activity.taskId, b.activity.taskId)
            a.onDestroyed = { observedClose.complete(observerScope.async { first.close() }) }
            a.cleanup = {
                assertFailsWith<IllegalStateException> { first.close() }
                assertFailsWith<IllegalStateException> { PluginOperationOwner.requireOutsideCall() }
                entered.complete(Unit)
                release.await()
            }
            val retiring = async { first.close() }
            withTimeout(5_000) { entered.await() }
            assertFalse(retiring.isCompleted)
            assertFalse(observedClose.await().isCompleted)
            assertEquals(1, a.destroyed)
            assertEquals(0, b.destroyed)
            second.show()
            release.complete(Unit)
            withTimeout(5_000) { retiring.await(); observedClose.await().await() }
            assertEquals(1, a.closed)
            assertFailsWith<IllegalStateException> { first.show() }
            assertFailsWith<IllegalStateException> { first.awaitReady() }
            first.close()
            assertEquals(1, a.closed)
            val providerOwner = PluginOperationOwner("window provider")
            providerOwner.run { second.close() }
            providerOwner.close()
            assertEquals(1, b.destroyed)
            assertEquals(1, b.closed)
        } finally {
            release.complete(Unit)
            try { first?.close() } finally {
                try { second?.close() } finally { observerScope.cancel(); instrumentation.uiAutomation.dropShellPermissionIdentity() }
            }
        }
    }

    @Test(timeout = 20_000)
    fun retiredPendingLaunchCannotAttachAndCanceledAcquisitionDoesNotLeaveAWindow(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var intent: Intent? = null
        var cancel: Job? = null
        val launchContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(value: Intent) { intent = value; cancel?.cancel() }
        }
        val host = NativeAndroidPluginWindowHost(launchContext)
        var created = 0
        val factory = AndroidPluginWindowFactory { created++; error("retired factory must not run") }
        val pending = host.open(factory)
        pending.close()
        assertFailsWith<IllegalStateException> { pending.awaitReady() }
        // Deliver the recorded OS launch late, after its admission lease has been retired.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            withContext(Dispatchers.Main.immediate) { context.startActivity(requireNotNull(intent)) }
            instrumentation.waitForIdleSync()
            assertEquals(0, created)
            val acquisition = async(Dispatchers.Main.immediate) {
                cancel = currentCoroutineContext()[Job]
                host.open(factory)
                error("canceled acquisition must not return a lease")
            }
            acquisition.join()
            assertTrue(acquisition.isCancelled)
            withContext(Dispatchers.Main.immediate) { context.startActivity(requireNotNull(intent)) }
            instrumentation.waitForIdleSync()
            assertEquals(0, created)
            val activity = withContext(Dispatchers.Main.immediate) {
                object : Activity() { override fun getApplicationContext(): Context = launchContext }
            }
            val inputs = AndroidPluginHostInputs(activity)
            val stale = inputs.windows()
            inputs.close()
            assertFailsWith<IllegalStateException> { stale.open(factory) }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    @Test(timeout = 30_000)
    fun osActivityFinishStillJoinsOwnedCleanupWhenThePluginRetires(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val content = Content("window-finished")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        content.cleanup = { entered.complete(Unit); release.await() }
        var window: AndroidPluginWindow? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            window = NativeAndroidPluginWindowHost(instrumentation.targetContext).open(
                AndroidPluginWindowFactory { content.also { value -> value.activity = it } },
            )
            withTimeout(10_000) { window.awaitReady() }
            withContext(Dispatchers.Main.immediate) { content.activity.finish() }
            withTimeout(5_000) { entered.await() }
            val retiring = async { window.close() }
            assertFalse(retiring.isCompleted)
            assertEquals(1, content.destroyed)
            release.complete(Unit)
            withTimeout(5_000) { retiring.await() }
            assertEquals(1, content.closed)
        } finally {
            release.complete(Unit)
            try { window?.close() } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
    }

    private class Content(private val text: String) : AndroidPluginWindowContent {
        lateinit var activity: Activity
        var destroyed = 0
        var closed = 0
        var cleanup: suspend () -> Unit = {}
        var onDestroyed: () -> Unit = {}
        override fun onCreate(savedInstanceState: Bundle?) { activity.setContentView(TextView(activity).apply { this.text = this@Content.text }) }
        override fun onDestroy() { destroyed++; onDestroyed() }
        override suspend fun close() { cleanup(); closed++ }
    }
}
