package ai.meteor.kcode.plugin.agentloop

import ai.meteor.kcode.skill.SkillTurnContext
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole

internal fun buildContext(messages: List<ChatMessage>, latestPrompt: String): String = buildString {
    appendLine("The following is the current conversation. Continue from its context and respond directly to the final user message.")
    messages.filterNot { it.isError }.forEach { message ->
        val role = if (message.role == MessageRole.User) "User" else "Assistant"
        appendLine("$role: ${message.content}")
    }
    append("User: $latestPrompt")
}


internal fun appendSelectedSkillFragments(turn: SkillTurnContext, userContext: String): String =
    if (turn.selectedSkillFragments.isEmpty()) userContext
    else turn.selectedSkillFragments.joinToString("\n\n", postfix = "\n\n$userContext")
