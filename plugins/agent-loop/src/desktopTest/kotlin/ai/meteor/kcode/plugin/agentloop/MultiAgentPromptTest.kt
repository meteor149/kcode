package ai.meteor.kcode.plugin.agentloop

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.serialization.typeToken
import ai.koog.agents.core.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MultiAgentPromptTest {
    @Test
    fun unknownCapacityDoesNotInventTheDefaultProvidersLimit() {
        kotlin.test.assertFalse(rootMultiAgentInstructions(null).contains("concurrency slots"))
        kotlin.test.assertFalse(subAgentInstructions(null).contains("concurrency slots"))
        assertContains(subAgentInstructions(2), "2 available concurrency slots")
    }

    @Test
    fun absentCoordinationToolsDoNotAdvertiseDelegation() {
        assertEquals("", availableMultiAgentInstructions(ToolRegistry { }, rootMultiAgentInstructions(3)))
    }

    @Test
    fun availableCoordinationToolsKeepCanonicalPathsAndDelegationPolicy() {
        val tools = ToolRegistry {
            tool(object : ToolBase<Unit, String>(
                typeToken<Unit>(), typeToken<String>(),
                ToolDescriptor("spawn_agent", "fixture", emptyList(), emptyList()),
            ) {
                override suspend fun execute(args: Unit, metadata: ToolCallMetadata) = "fixture"
            })
        }
        val prompt = availableMultiAgentInstructions(tools, rootMultiAgentInstructions(3))
        assertContains(prompt, "You are `/root`, the primary agent")
        assertContains(prompt, "up to 3 agents can be active at once")
        assertContains(prompt, "Proactive multi-agent delegation is active")
        assertContains(prompt, "<multi_agent_mode>")
    }
}
