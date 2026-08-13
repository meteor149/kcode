package ai.meteor.kcode

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

internal class DesktopScheduledTaskPlatformHost(
    private val window: ComposeWindow,
) : ScheduledTaskPlatformHost {
    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            val image = Toolkit.getDefaultToolkit().getImage(
                requireNotNull(javaClass.getResource("/kcode-icon.png")),
            )
            TrayIcon(image, "kcode").also { icon ->
                icon.isImageAutoSize = true
                icon.addActionListener {
                    window.isVisible = true
                    window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                    window.toFront()
                    window.requestFocus()
                }
                SystemTray.getSystemTray().add(icon)
            }
        }.getOrNull()
    }

    override fun isAppInForeground(): Boolean =
        window.isVisible && window.isFocused && window.extendedState and Frame.ICONIFIED == 0

    override fun showTriggeredNotification(title: String, body: String) {
        trayIcon?.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }
}
