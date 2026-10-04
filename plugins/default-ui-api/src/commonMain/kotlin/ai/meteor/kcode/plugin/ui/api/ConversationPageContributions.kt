package ai.meteor.kcode.plugin.ui.api

import androidx.compose.runtime.key
import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.ui.state.ConversationState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** A page supplies generic execution inputs; individual contributions own domain behavior. */
data class ConversationPageContext(
    val conversation: ConversationState?,
    val compact: Boolean,
    val configuration: ModelConfiguration?,
    val service: ChatService,
    val generationRunner: ChatGenerationRunner,
    val scheduledTaskCoordinator: ScheduledTaskCoordinator,
    val failureMessages: ChatFailureMessages,
    val followBottom: (ConversationState) -> Unit,
)

fun interface ConversationDecorationPresenter {
    @Composable
    fun Present(context: ConversationPageContext): ConversationDecorationContent?
}

data class ConversationDecorationContent(
    val occupiedHeight: Dp,
    val renderer: UiRenderer<Modifier>,
) {
    init { require(occupiedHeight.value.isFinite() && occupiedHeight.value >= 0f) }
}

data class ConversationDecoration(
    val id: String,
    val order: Int,
    val presenter: ConversationDecorationPresenter,
)

data class ConversationPageEffect(
    val id: String,
    val order: Int,
    val renderer: UiRenderer<ConversationPageContext>,
)

/** Called once per page; owned Compose effects disappear with their committed contributions. */
@Composable
fun PresentConversationContributions(
    context: ConversationPageContext,
    slots: ApplicationUiSlots,
): List<Pair<ConversationDecoration, ConversationDecorationContent>> {
    slots.conversationEffects.forEach { effect ->
        key(effect) { effect.renderer.Render(context) }
    }
    return slots.conversationDecorations.mapNotNull { decoration ->
        key(decoration) { decoration.presenter.Present(context) }?.let { decoration to it }
    }
}
