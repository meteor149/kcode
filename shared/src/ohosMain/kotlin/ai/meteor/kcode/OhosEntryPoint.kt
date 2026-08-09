package ai.meteor.kcode

import androidx.compose.ui.window.ComposeArkUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.initMainHandler
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_env
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_value
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("MainArkUIViewController")
fun MainArkUIViewController(env: napi_env): napi_value {
    initMainHandler(env)
    return ComposeArkUIViewController(env) {
        KcodeApp(
            chatService = OhosChatService,
            settingsStore = OhosAppSettingsStore,
            historyRepository = OhosConversationHistoryRepository,
        )
    }
}
