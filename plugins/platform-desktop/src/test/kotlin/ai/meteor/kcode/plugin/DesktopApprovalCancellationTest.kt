package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.DesktopConfirmationDialogHost
import ai.meteor.kcode.plugin.api.ConfirmationDialogRequest
import java.awt.GraphicsEnvironment
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JOptionPane
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeFalse

class DesktopApprovalCancellationTest {
    @Test
    fun cancellingARealModalApprovalDisposesItsWindowBeforeReturning() = runBlocking {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val marker = "kcode-cancel-dialog-${System.nanoTime()}"
        val before = withContext(Dispatchers.Swing) { Window.getWindows().toSet() }
        val approval = async(Dispatchers.Default) {
            DesktopConfirmationDialogHost { null }.confirm(ConfirmationDialogRequest("confirmation", marker, "yes", "no"))
        }
        var owned: JDialog? = null
        try {
            withTimeout(10_000) {
                while (owned == null) {
                    owned = withContext(Dispatchers.Swing) {
                        Window.getWindows().filterIsInstance<JDialog>().firstOrNull { dialog ->
                            dialog !in before && dialog.isShowing && dialog.contentPane.components
                                .filterIsInstance<JOptionPane>().any { marker in it.message.toString() }
                        }
                    }
                    if (owned == null) delay(10)
                }
            }
            assertFalse(approval.isCompleted)
            withTimeout(10_000) { approval.cancelAndJoin() }
            withContext(Dispatchers.Swing) { assertFalse(checkNotNull(owned).isDisplayable) }
            assertTrue(approval.isCancelled)
        } finally {
            approval.cancel()
            withContext(Dispatchers.Swing) { owned?.dispose() }
            withTimeout(10_000) { approval.join() }
        }
    }
}
