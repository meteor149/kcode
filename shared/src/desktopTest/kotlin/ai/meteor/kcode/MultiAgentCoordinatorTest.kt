package ai.meteor.kcode

import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiAgentCoordinatorTest {
    @Test
    fun exposesTheCodexV2CollaborationToolSurface() = runTest {
        val coordinator = coordinator { "done" }
        val registry = coordinator.toolsFor(RootAgentPath)

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

    @Test
    fun spawnsCanonicalTaskPathsAndDeliversFinalAnswersToTheParentMailbox() = runTest {
        val releases = mutableMapOf<String, CompletableDeferred<String>>()
        val events = mutableListOf<SubAgentEvent>()
        val coordinator = MultiAgentCoordinator(
            scope = backgroundScope,
            rootContext = "root context",
            runAgent = { launch -> releases.getOrPut(launch.path) { CompletableDeferred() }.await() },
            onEvent = events::add,
        )

        assertContains(
            coordinator.spawn(RootAgentPath, "research", "Inspect the runtime", "all"),
            "/root/research",
        )
        runCurrent()
        assertEquals(
            SubAgentEvent.Spawned(
                path = "/root/research",
                parentPath = RootAgentPath,
                taskName = "research",
                prompt = "Inspect the runtime",
            ),
            events.first(),
        )

        releases.getValue("/root/research").complete("Runtime findings")
        runCurrent()

        val mailbox = coordinator.drainMailbox(RootAgentPath)
        assertContains(mailbox, "Message Type: FINAL_ANSWER")
        assertContains(mailbox, "Sender: /root/research")
        assertContains(mailbox, "Runtime findings")
        assertEquals(SubAgentStatus.Completed, coordinator.snapshots().single().status)
    }

    @Test
    fun enforcesFourTotalConcurrencySlotsIncludingRoot() = runTest {
        val never = CompletableDeferred<String>()
        val coordinator = coordinator { never.await() }

        coordinator.spawn(RootAgentPath, "one", "one", null)
        coordinator.spawn(RootAgentPath, "two", "two", null)
        coordinator.spawn(RootAgentPath, "three", "three", null)

        assertFailsWith<IllegalArgumentException> {
            coordinator.spawn(RootAgentPath, "four", "four", null)
        }
    }

    @Test
    fun supportsNestedAgentsAndPartialTurnForks() = runTest {
        val launches = Channel<SubAgentLaunch>(Channel.UNLIMITED)
        val coordinator = coordinator { launch ->
            launches.send(launch)
            "answer from ${launch.path}"
        }

        coordinator.spawn(RootAgentPath, "parent", "parent task", "all")
        advanceUntilIdle()
        launches.receive()
        coordinator.spawn("/root/parent", "child", "child task", "1")
        advanceUntilIdle()
        val child = launches.receive()

        assertEquals("/root/parent/child", child.path)
        assertTrue("Agent response: answer from /root/parent" in child.inheritedContext)
        assertTrue("root context" !in child.inheritedContext)
    }

    @Test
    fun followupTaskReusesAnIdleAgent() = runTest {
        val prompts = Channel<String>(Channel.UNLIMITED)
        val coordinator = coordinator { launch ->
            prompts.send(launch.prompt)
            "finished ${launch.prompt}"
        }

        coordinator.spawn(RootAgentPath, "worker", "first", null)
        advanceUntilIdle()
        assertEquals("first", prompts.receive())

        coordinator.followupTask(RootAgentPath, "worker", "second")
        advanceUntilIdle()
        assertEquals("second", prompts.receive())
        assertContains(coordinator.drainMailbox(RootAgentPath), "finished second")
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        runner: suspend (SubAgentLaunch) -> String,
    ): MultiAgentCoordinator = MultiAgentCoordinator(
        scope = backgroundScope,
        rootContext = "root context",
        runAgent = runner,
    )
}
