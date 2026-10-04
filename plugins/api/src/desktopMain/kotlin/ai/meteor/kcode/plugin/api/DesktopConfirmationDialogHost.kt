package ai.meteor.kcode.plugin.api

import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** Window ownership, Swing callbacks and cancellation; all product content comes from plugins. */
class DesktopConfirmationDialogHost(private val window: () -> Frame?) : ConfirmationDialogHost {
    override suspend fun confirm(request: ConfirmationDialogRequest): Boolean = withContext(Dispatchers.Swing) {
        val pane = JOptionPane(request.message, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION,
            null, arrayOf(request.confirmLabel, request.cancelLabel), request.cancelLabel)
        val dialog = pane.createDialog(window(), request.title)
        try {
            suspendCancellableCoroutine { continuation ->
                pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY) {
                    if (pane.value != JOptionPane.UNINITIALIZED_VALUE && continuation.isActive) {
                        continuation.resume(pane.value == request.confirmLabel)
                    }
                }
                dialog.addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(event: WindowEvent) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    override fun windowClosing(event: WindowEvent) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
                continuation.invokeOnCancellation { SwingUtilities.invokeLater { dialog.dispose() } }
                SwingUtilities.invokeLater { if (continuation.isActive) dialog.isVisible = true else dialog.dispose() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Swing) { dialog.dispose() }
        }
    }
}
