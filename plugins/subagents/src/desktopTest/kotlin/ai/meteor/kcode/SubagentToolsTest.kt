package ai.meteor.kcode

import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentToolsTest {
    @Test
    fun exposesTheCodexV2CollaborationToolSurface() = runTest {
        val coordinator = MultiAgentCoordinator(backgroundScope, "context", runAgent = { "done" })
        val registry = subagentTools(coordinator, RootAgentPath)

        assertEquals(
            setOf(
                "spawn_agent",
                "send_message",
                "followup_task",
                "interrupt_agent",
                "list_agents",
                "wait_agent",
            ),
            registry.tools.map { it.name }.toSet(),
        )
        val spawn = requireNotNull(registry.getToolOrNull("spawn_agent"))
        assertEquals(
            setOf("task_name", "message"),
            spawn.descriptor.requiredParameters.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("fork_turns"),
            spawn.descriptor.optionalParameters.map { it.name }.toSet(),
        )
    }

}
