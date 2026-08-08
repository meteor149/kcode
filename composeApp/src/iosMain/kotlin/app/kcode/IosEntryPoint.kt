package app.kcode

import androidx.compose.ui.window.ComposeUIViewController
import app.kcode.settings.IosAppSettingsStore
import app.kcode.history.createIosConversationHistoryRepository
import platform.UIKit.UIViewController

/** UIKit/SwiftUI host entry point. The complete screen tree comes from commonMain. */
fun MainViewController(): UIViewController {
    val settingsStore = IosAppSettingsStore()
    val permissionState = IosToolPermissionState()
    var viewController: UIViewController? = null
    val chatService = createIosKoogChatService(
        settingsStore = settingsStore,
        workspaceRoot = createIosAgentWorkspaceRoot(),
        permissionState = permissionState,
        presentingViewController = { viewController },
    )
    return ComposeUIViewController {
        KcodeApp(
            chatService = chatService,
            settingsStore = settingsStore,
            historyRepository = createIosConversationHistoryRepository(),
            toolPermissionControlsAvailable = true,
            onToolPermissionModeChanged = { permissionState.mode = it },
        )
    }.also { viewController = it }
}
