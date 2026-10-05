package ai.meteor.kcode

import androidx.compose.runtime.DisposableEffect
import java.util.concurrent.atomic.AtomicReference
import java.awt.Frame
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import kotlinx.coroutines.launch

fun main() {
    val applicationWindow = AtomicReference<Frame?>()
    val runtime = createDesktopKoogChatRuntime(applicationWindow = applicationWindow::get)
    application {
        val retirementScope = rememberCoroutineScope()
        val closing = remember { mutableStateOf(false) }
        val appIcon = painterResource(
            if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
                "kcode-icon-macos.png"
            } else {
                "kcode-icon.png"
            },
        )
        val state = rememberWindowState(
            size = DpSize(1180.dp, 780.dp),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = {
                if (!closing.value) {
                    closing.value = true
                    retirementScope.launch {
                        try {
                            runtime.close()
                            exitApplication()
                        } finally {
                            closing.value = false
                        }
                    }
                }
            },
            state = state,
            title = "kcode",
            icon = appIcon,
        ) {
            DisposableEffect(window) {
                applicationWindow.set(window)
                onDispose { applicationWindow.compareAndSet(window, null) }
            }
            checkNotNull(runtime.applicationContent).Render(
                ApplicationHostOptions(
                    conversationSettingsControlsAvailable = true,
                ),
            )
        }
    }
}
