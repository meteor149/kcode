package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatAvailability
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatServiceUnavailable
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
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
        KcodeApp(chatService = OhosBootstrapChatService)
    }
}

private object OhosBootstrapChatService : ChatService {
    override val availability = ChatAvailability.BrowserGateway

    override suspend fun reply(
        configuration: ModelConfiguration,
        history: List<ChatMessage>,
        prompt: String,
    ): String = throw ChatServiceUnavailable(availability)
}
