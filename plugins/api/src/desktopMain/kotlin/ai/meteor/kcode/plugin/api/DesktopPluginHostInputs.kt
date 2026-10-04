package ai.meteor.kcode.plugin.api

import java.awt.Frame
import java.util.concurrent.atomic.AtomicReference

/** Read the current OS window when needed; the host binds/unbinds it independently of plugins. */
class DesktopPluginHostInputs private constructor(
    applicationWindow: () -> Frame?,
    dialogProvider: () -> ConfirmationDialogHost,
) : PluginHostInputs() {
    constructor(applicationWindow: () -> Frame?) : this(applicationWindow, { DesktopConfirmationDialogHost(applicationWindow) })
    constructor(applicationWindow: () -> Frame?, dialogs: ConfirmationDialogHost) : this(applicationWindow, { dialogs })

    private val dialogSource = AtomicReference<(() -> ConfirmationDialogHost)?>(dialogProvider)
    override fun confirmationDialogs(): ConfirmationDialogHost = ConfirmationDialogHost { request ->
        checkNotNull(dialogSource.get()) { "Desktop host inputs are closed" }.invoke().confirm(request)
    }

    private val source = AtomicReference<(() -> Frame?)?>(applicationWindow)

    fun applicationWindow(): Frame? = checkNotNull(source.get()) { "Desktop host inputs are closed" }.invoke()

    override fun createLease(): DesktopPluginHostInputs {
        checkNotNull(source.get()) { "Desktop host inputs are closed" }
        return DesktopPluginHostInputs({ applicationWindow() }, { confirmationDialogs() })
    }

    override suspend fun close() {
        source.set(null)
        dialogSource.set(null)
    }
}
