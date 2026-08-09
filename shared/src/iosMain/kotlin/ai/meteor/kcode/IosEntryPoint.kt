package ai.meteor.kcode

import androidx.compose.ui.window.ComposeUIViewController
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.settings.IosAppSettingsStore
import ai.meteor.kcode.history.createIosConversationHistoryRepository
import platform.UIKit.UIViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/** UIKit/SwiftUI host entry point. The complete screen tree comes from commonMain. */
fun MainViewController(): UIViewController {
    val settingsStore = IosAppSettingsStore()
    val permissionState = IosToolPermissionState()
    var viewController: UIViewController? = null
    var backgroundTask = UIBackgroundTaskInvalid
    lateinit var generationRunner: ChatGenerationRunner

    fun endBackgroundTask() {
        if (backgroundTask == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(backgroundTask)
        backgroundTask = UIBackgroundTaskInvalid
    }

    generationRunner = ChatGenerationRunner(onActiveChanged = { active ->
        if (active && backgroundTask == UIBackgroundTaskInvalid) {
            backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler {
                generationRunner.cancelAll()
                endBackgroundTask()
            }
        } else if (!active) {
            endBackgroundTask()
        }
    })
    val runtime = createIosKoogChatRuntime(
        settingsStore = settingsStore,
        workspaceRoot = createIosAgentWorkspaceRoot(),
        permissionState = permissionState,
        presentingViewController = { viewController },
    )
    return ComposeUIViewController {
        KcodeApp(
            chatService = runtime.chatService,
            generationRunner = generationRunner,
            webContainerController = runtime.webContainerController,
            settingsStore = settingsStore,
            historyRepository = createIosConversationHistoryRepository(),
            toolPermissionControlsAvailable = true,
            onToolPermissionModeChanged = { permissionState.mode = it },
        )
    }.also { viewController = it }
}
