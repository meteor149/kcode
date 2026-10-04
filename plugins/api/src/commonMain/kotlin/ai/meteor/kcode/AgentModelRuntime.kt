package ai.meteor.kcode

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel

data class AgentModelRuntime(
    val client: LLMClient,
    val model: LLModel,
)
