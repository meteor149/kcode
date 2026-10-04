package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.AndroidPermissionRequestBroker
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPermissionBrokerTest {
    @Test(timeout = 30_000)
    fun canceledDialogCannotDeliverToTheNextPermissionAndCloseReleasesWaiters(): Unit = runBlocking {
        val launched = CopyOnWriteArrayList<String>()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
        }
        val broker = AndroidPermissionRequestBroker({ context }, launched::add)
        try {
            val first = async(Dispatchers.Main.immediate) { broker.request("test.permission.FIRST") }
            withTimeout(5_000) { while (launched.size != 1) delay(10) }
            first.cancelAndJoin()
            val second = async(Dispatchers.Main.immediate) { broker.request("test.permission.SECOND") }
            withContext(Dispatchers.Main.immediate) {
                assertEquals(listOf("test.permission.FIRST"), launched.toList())
                broker.onResult("test.permission.UNRELATED", true)
                assertFalse(second.isCompleted)
                broker.onResult("test.permission.FIRST", true)
            }
            withTimeout(5_000) { while (launched.size != 2) delay(10) }
            withContext(Dispatchers.Main.immediate) {
                broker.onResult("test.permission.FIRST", false)
                assertFalse(second.isCompleted)
                broker.onResult("test.permission.SECOND", true)
            }
            assertTrue(second.await())
            val canceled = async(Dispatchers.Main.immediate) { broker.request("test.permission.CANCELED") }
            withTimeout(5_000) { while (launched.size != 3) delay(10) }
            withContext(Dispatchers.Main.immediate) { broker.onResult(emptyMap()) }
            assertFalse(canceled.await())
            val last = async(Dispatchers.Main.immediate) { broker.request("test.permission.LAST") }
            withTimeout(5_000) { while (launched.size != 4) delay(10) }
            withContext(Dispatchers.Main.immediate) { broker.close() }
            assertFalse(last.await())
            assertFailsWith<IllegalStateException> { broker.request("test.permission.CLOSED") }
        } finally { withContext(Dispatchers.Main.immediate) { broker.close() } }
    }

    @Test(timeout = 30_000)
    fun grantedPermissionsDoNotLaunchAndLaunchFailureLeavesNoPendingDialog(): Unit = runBlocking {
        val launched = CopyOnWriteArrayList<String>()
        var fail = true
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun checkSelfPermission(permission: String) =
                if (permission == "test.permission.GRANTED") PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val broker = AndroidPermissionRequestBroker({ context }) { permission ->
            if (fail) error("Launcher unavailable")
            launched += permission
        }
        try {
            assertTrue(broker.request("test.permission.GRANTED"))
            assertTrue(launched.isEmpty())
            assertFailsWith<IllegalStateException> { broker.request("test.permission.DENIED") }
            fail = false
            val request = async(Dispatchers.Main.immediate) { broker.request("test.permission.DENIED") }
            withTimeout(5_000) { while (launched.isEmpty()) delay(10) }
            withContext(Dispatchers.Main.immediate) { broker.onResult("test.permission.DENIED", false) }
            assertFalse(request.await())
            assertFailsWith<IllegalArgumentException> { broker.request("") }
        } finally { withContext(Dispatchers.Main.immediate) { broker.close() } }
    }
}
