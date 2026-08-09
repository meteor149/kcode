package ai.meteor.kcode

import ai.koog.prompt.executor.clients.LLMClient
import ai.meteor.kcode.model.ModelConfiguration

internal actual fun createBedrockClient(configuration: ModelConfiguration): LLMClient =
    throw UnsupportedOperationException(
        "Amazon Bedrock is not available in the iOS Koog client. Choose another provider.",
    )
