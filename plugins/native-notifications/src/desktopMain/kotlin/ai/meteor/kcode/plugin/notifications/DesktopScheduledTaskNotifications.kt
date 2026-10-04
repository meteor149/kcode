package ai.meteor.kcode.plugin.notifications

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsResource
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.ActionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

fun desktopScheduledTaskNotificationsFactory(applicationWindow: () -> Frame?) = ScheduledTaskNotificationsFactory {
    val host = DesktopScheduledTaskNotifications(applicationWindow)
    ScheduledTaskNotificationsResource(host, host::close)
}

internal interface DesktopNotificationTray {
    fun show(title: String, body: String)
    fun close()
}

internal class DesktopScheduledTaskNotifications(
    private val applicationWindow: () -> Frame?,
    private val createTray: (() -> Unit) -> DesktopNotificationTray? = ::createAwtNotificationTray,
) : ScheduledTaskPlatformHost {
    private var closed = false
    private var tray: DesktopNotificationTray? = null

    override suspend fun isAppInForeground(): Boolean = withContext(Dispatchers.Swing) {
        check(!closed) { "Notifications are closed" }
        applicationWindow()?.let { it.isVisible && it.isFocused && it.extendedState and Frame.ICONIFIED == 0 } == true
    }

    override suspend fun showTriggeredNotification(title: String, body: String) = withContext(Dispatchers.Swing) {
        check(!closed) { "Notifications are closed" }
        val current = tray ?: createTray {
            if (!closed) applicationWindow()?.let { window ->
                window.isVisible = true
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                window.toFront()
                window.requestFocus()
            }
        }?.also { tray = it }
        current?.show(title, body)
        Unit
    }

    suspend fun close() = withContext(Dispatchers.Swing) {
        if (!closed) {
            closed = true
            val previous = tray
            tray = null
            previous?.close()
        }
    }
}

internal fun createAwtNotificationTray(revealWindow: () -> Unit): DesktopNotificationTray? {
    if (!SystemTray.isSupported()) return null
    val systemTray = SystemTray.getSystemTray()
    val image = Toolkit.getDefaultToolkit().getImage(
        requireNotNull(DesktopScheduledTaskNotifications::class.java.getResource("/kcode-notification-icon.png")),
    )
    val icon = TrayIcon(image, "kcode").apply { isImageAutoSize = true }
    val listener = ActionListener { revealWindow() }
    icon.addActionListener(listener)
    return try {
        systemTray.add(icon)
        object : DesktopNotificationTray {
            override fun show(title: String, body: String) = icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
            override fun close() {
                icon.removeActionListener(listener)
                systemTray.remove(icon)
                image.flush()
            }
        }
    } catch (error: Throwable) {
        icon.removeActionListener(listener)
        image.flush()
        if (error is java.awt.AWTException) null else throw error
    }
}
