package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.chat.ConversationCommandSnapshot
import androidx.compose.runtime.staticCompositionLocalOf

val LocalConversationCommands = staticCompositionLocalOf { ConversationCommandSnapshot() }
