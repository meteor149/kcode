package ai.meteor.kcode

import ai.koog.prompt.executor.clients.LLMClient
import ai.meteor.kcode.model.ModelConfiguration

internal actual fun createBedrockClient(configuration: ModelConfiguration): LLMClient =
    throw UnsupportedOperationException(
        "Koog's Amazon Bedrock client is JVM-only and is not published for Android. Use kcode Desktop for Bedrock.",
    )
