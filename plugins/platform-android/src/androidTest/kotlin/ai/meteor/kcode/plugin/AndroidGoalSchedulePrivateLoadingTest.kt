package ai.meteor.kcode.plugin

import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidGoalSchedulePrivateLoadingTest {
    @Test(timeout = 60000)
    fun realApkUsesPrivateGoalAndScheduleImplementations(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "goal-schedule-${System.nanoTime()}").apply { mkdirs() }
        val goalArtifact = File(directory, "providers.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(goalArtifact)
        check(goalArtifact.setReadOnly())
        val scheduleArtifact = goalArtifact
        lateinit var goals: GoalSessionFactory
        lateinit var schedules: ScheduledTaskCoordinator
        val capture = kcodePlugin(PluginDescriptor("test.goal-schedule", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-goal-schedule", inject = dependencies(KcodeGoals.Key, KcodeSchedules.Key)) { ctx, _ ->
                goals = ctx.require(KcodeGoals.Key).sessions
                schedules = ctx.require(KcodeSchedules.Key).coordinator
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        fun spec(id: String, artifact: File, entry: String) = DynamicPluginSpec(
            id = id, version = "private", artifactPath = artifact.path,
            sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
            entryClass = entry, packageName = instrumentation.context.packageName,
        )
        try {
            runtime.pluginManager.replace(spec("provider.goal-sessions.history", goalArtifact, GoalSessionProviderPlugin::class.java.name))
            runtime.pluginManager.replace(spec("provider.schedules.history", scheduleArtifact, ScheduledTaskProviderPlugin::class.java.name))
            val originalGoals = goals
            val originalSchedules = schedules
            assertEquals("ai.meteor.kcode.plugin.goal.HistoryGoalSessions", originalGoals.javaClass.name)
            assertEquals("ai.meteor.kcode.plugin.schedule.HistoryScheduledTaskCoordinator", originalSchedules.javaClass.name)
            assertNotSame(GoalSessionFactory::class.java.classLoader, originalGoals.javaClass.classLoader)
            assertNotSame(ScheduledTaskCoordinator::class.java.classLoader, originalSchedules.javaClass.classLoader)
            val conversation = HistoryConversationState(91, "private provider")
            val goal = requireNotNull(originalGoals.create(conversation))
            val schedule = requireNotNull(originalSchedules.sessionFor(conversation.id, conversation.title))
            assertEquals("ai.meteor.kcode.plugin.goal.ConversationGoalSession", goal.javaClass.name)
            assertEquals("ai.meteor.kcode.plugin.schedule.ConversationScheduledTaskSession", schedule.javaClass.name)
            assertEquals(originalGoals.javaClass.classLoader, goal.javaClass.classLoader)
            assertEquals(originalSchedules.javaClass.classLoader, schedule.javaClass.classLoader)
            goal.setGoalFromUser("verify private implementation")
            val task = schedule.create("private task", "check state", 60, null, null)
            assertTrue(schedule.list().any { it.taskId == task.taskId })
            runtime.pluginManager.setEnabled("provider.goal-sessions.history", false)
            assertFailsWith<IllegalStateException> { originalGoals.create(conversation) }
            assertFailsWith<IllegalStateException> { goal.getGoal() }
            runtime.pluginManager.setEnabled("provider.goal-sessions.history", true)
            assertNotSame(originalGoals, goals)
            assertEquals("verify private implementation", requireNotNull(goals.create(conversation)).getGoal()?.objective)
            runtime.pluginManager.setEnabled("provider.schedules.history", false)
            assertFailsWith<IllegalStateException> { originalSchedules.sessionFor(91, "stale") }
            assertFailsWith<IllegalStateException> { schedule.list() }
            runtime.pluginManager.setEnabled("provider.schedules.history", true)
            assertNotSame(originalSchedules, schedules)
            assertTrue(requireNotNull(schedules.sessionFor(91, conversation.title)).list().any { it.taskId == task.taskId })
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
