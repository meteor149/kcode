package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentContinuationContext
import ai.meteor.kcode.plugin.api.KcodeContinuations
import ai.meteor.kcode.plugin.api.KcodeSubagents
import ai.meteor.kcode.plugin.api.KcodeTools
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.cordis.Context
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubagentFeatureLifecycleTest {
    @Test
    fun featureSurvivesRegistryWithdrawalAndRetractsEveryContributionOnDisposal() = runTest {
        val context = Context()
        try {
            val feature = context.plugin(SubagentFeaturePlugin,
                Json.parseToJsonElement("""{"maxConcurrency":3}""")).await()
            val factory = context.require(KcodeSubagents.Key).factory
            assertEquals(3, factory.maxConcurrency)
            val registries = plugin<Unit>(name = "test.subagent.registries") { ctx, _ ->
                KcodeTools(ctx)
                KcodeContinuations(ctx)
            }
            val first = context.plugin(registries, Unit).await()
            val continuation = AgentContinuationContext({ "Continue subagent work" }, { null })
            suspend fun awaitContributions() = withTimeout(5_000) {
                while (context.require(KcodeTools.Key).contributionIds().isEmpty() ||
                    context.require(KcodeContinuations.Key).next(continuation) == null) {
                    delay(1)
                }
            }
            awaitContributions()
            assertEquals(listOf("core/subagent"), context.require(KcodeTools.Key).contributionIds())
            assertEquals("Continue subagent work", context.require(KcodeContinuations.Key).next(continuation))
            first.dispose()
            assertEquals(3, factory.maxConcurrency)
            val restored = context.plugin(registries, Unit).await()
            val tools = context.require(KcodeTools.Key)
            val policies = context.require(KcodeContinuations.Key)
            awaitContributions()
            assertEquals(listOf("core/subagent"), tools.contributionIds())
            assertEquals("Continue subagent work", policies.next(continuation))
            feature.dispose()
            assertNull(context[KcodeSubagents.Key])
            assertNull(factory.maxConcurrency)
            assertEquals(emptyList(), tools.contributionIds())
            assertNull(policies.next(continuation))
            restored.dispose()
        } finally { context.fiber.dispose() }
    }
}
