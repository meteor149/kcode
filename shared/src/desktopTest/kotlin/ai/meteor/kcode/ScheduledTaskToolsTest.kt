package ai.meteor.kcode

import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.history.ScheduledTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduledTaskToolsTest {
    @Test
    fun exposesSingleScheduledTaskManagementTool() {
        val registry = scheduledTaskTools(FakeScheduledTaskSession())
        val tool = requireNotNull(registry.getToolOrNull("schedule_task"))

        assertEquals(listOf("schedule_task"), registry.tools.map { it.name })
        assertTrue(tool.descriptor.description.contains("create_recurring"))
        assertEquals(setOf("action"), tool.descriptor.requiredParameters.map { it.name }.toSet())
        assertEquals(
            setOf(
                "name",
                "prompt",
                "delay_seconds",
                "run_at_epoch_millis",
                "repeat_interval_seconds",
                "task_id",
            ),
            tool.descriptor.optionalParameters.map { it.name }.toSet(),
        )
    }

    @Test
    fun exposesStandaloneCompletionToolWithRequiredResult() {
        val registry = scheduledTaskCompletionTools(ScheduledTaskCompletionSession {})
        val tool = requireNotNull(registry.getToolOrNull("complete_scheduled_task"))

        assertEquals(listOf("complete_scheduled_task"), registry.tools.map { it.name })
        assertEquals(setOf("result"), tool.descriptor.requiredParameters.map { it.name }.toSet())
        assertTrue(tool.descriptor.optionalParameters.isEmpty())
    }
}

private class FakeScheduledTaskSession : ScheduledTaskSession {
    override fun currentTimeEpochMillis(): Long = 1L
    override suspend fun list(): List<ScheduledTask> = emptyList()

    override suspend fun create(
        name: String,
        prompt: String,
        delaySeconds: Long?,
        runAtEpochMillis: Long?,
        repeatIntervalSeconds: Long?,
    ): ScheduledTask = error("unused")

    override suspend fun pause(taskId: String): ScheduledTask = error("unused")
    override suspend fun resume(taskId: String): ScheduledTask = error("unused")
    override suspend fun cancel(taskId: String) = Unit
}
