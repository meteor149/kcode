package ai.meteor.kcode.plugin.notifications

import java.awt.EventQueue
import java.awt.SystemTray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class DesktopScheduledTaskNotificationsTest {
    @Test
    fun ownsOneTrayAndClosesItOnEdtWithoutAllocatingDuringClose() = runTest {
        var allocated = 0
        var shown = 0
        var closed = 0
        var windowLookups = 0
        lateinit var retiredAction: () -> Unit
        val host = DesktopScheduledTaskNotifications({ windowLookups++; null }) { action ->
            retiredAction = action
            assertTrue(EventQueue.isDispatchThread())
            allocated++
            object : DesktopNotificationTray {
                override fun show(title: String, body: String) {
                    assertTrue(EventQueue.isDispatchThread())
                    assertEquals("title", title)
                    assertEquals("body", body)
                    shown++
                }
                override fun close() {
                    assertTrue(EventQueue.isDispatchThread())
                    closed++
                }
            }
        }
        assertEquals(0, allocated)
        assertEquals(false, host.isAppInForeground())
        host.showTriggeredNotification("title", "body")
        host.showTriggeredNotification("title", "body")
        assertEquals(1, allocated)
        assertEquals(2, shown)
        host.close()
        host.close()
        assertEquals(1, closed)
        val previousLookups = windowLookups
        withContext(Dispatchers.Swing) { retiredAction() }
        assertEquals(previousLookups, windowLookups)
        assertFailsWith<IllegalStateException> { host.showTriggeredNotification("title", "body") }
        val unused = DesktopScheduledTaskNotifications({ null }) { error("Close must not allocate a tray") }
        unused.close()
    }

    @Test
    fun actualAwtTrayRestoresOsInventoryAndRemovesItsListener() = runTest {
        withContext(Dispatchers.Swing) {
            val supported = SystemTray.isSupported()
            println("Native SystemTray supported=$supported")
            val before = if (supported) SystemTray.getSystemTray().trayIcons.toSet() else emptySet()
            val resource = createAwtNotificationTray {}
            if (!supported) {
                assertNull(resource)
            } else {
                assertNotNull(resource)
                val added = (SystemTray.getSystemTray().trayIcons.toSet() - before).single()
                try {
                    assertEquals(1, added.actionListeners.size)
                } finally { resource.close() }
                assertEquals(0, added.actionListeners.size)
                assertEquals(before, SystemTray.getSystemTray().trayIcons.toSet())
            }
        }
    }
}
