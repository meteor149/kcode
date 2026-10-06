package ai.meteor.kcode

import ai.meteor.kcode.plugin.recovery.ProfileHostContent
import java.util.Locale
import kotlinx.coroutines.runBlocking

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

fun main(args: Array<String>) {
    val profileId = when {
        args.isEmpty() -> null
        args.size == 2 && args[0] == "--profile" -> args[1]
        args.size == 1 && args[0].startsWith("--profile=") -> args[0].substringAfter('=')
        else -> error("Usage: kcode [--profile <id>]")
    }
    val applicationWindow = AtomicReference<Frame?>()
    val host = runBlocking { createDesktopProfileHost(applicationWindow = applicationWindow::get, profileId = profileId) }
    val runtime = host.runtime
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
            ProfileHostContent(host,
                ApplicationHostOptions(
                    conversationSettingsControlsAvailable = true,
                ),
                Locale.getDefault().language,
            )
        }
    }
}
