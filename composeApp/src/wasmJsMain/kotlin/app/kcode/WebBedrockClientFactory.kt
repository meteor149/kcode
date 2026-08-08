package app.kcode

import ai.koog.prompt.executor.clients.LLMClient
import app.kcode.model.ModelConfiguration

internal actual fun createBedrockClient(configuration: ModelConfiguration): LLMClient =
    throw UnsupportedOperationException(
        "Amazon Bedrock is not available in the browser Koog client. Choose another provider.",
    )
