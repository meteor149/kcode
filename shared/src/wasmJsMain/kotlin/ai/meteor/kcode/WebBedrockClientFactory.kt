package ai.meteor.kcode

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.meteor.kcode.model.ModelConfiguration

internal actual fun createBedrockClient(configuration: ModelConfiguration): LLMClient =
    throw UnsupportedOperationException(
        "Amazon Bedrock is not available in the browser Koog client. Choose another provider.",
    )

internal actual fun createBedrockModel(modelId: String): LLModel =
    throw UnsupportedOperationException("Amazon Bedrock models are not available in the browser.")
