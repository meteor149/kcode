package ai.meteor.kcode

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.meteor.kcode.model.ModelConfiguration
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.collections.mutableAttributes
import aws.smithy.kotlin.runtime.http.auth.BearerToken
import aws.smithy.kotlin.runtime.http.auth.BearerTokenProvider
import aws.smithy.kotlin.runtime.time.Instant

internal actual fun createBedrockClient(configuration: ModelConfiguration): LLMClient =
    BedrockLLMClient(
        identityProvider = object : BearerTokenProvider {
            override suspend fun resolve(attributes: Attributes): BearerToken = object : BearerToken {
                override val token: String = configuration.apiKey
                override val attributes: Attributes = mutableAttributes()
                override val expiration: Instant? = null
            }
        },
        settings = BedrockClientSettings(region = configuration.region),
    )
