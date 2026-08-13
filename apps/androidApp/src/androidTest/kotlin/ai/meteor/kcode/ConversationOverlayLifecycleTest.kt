package ai.meteor.kcode

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationOverlayLifecycleTest {
    @Test
    fun showsWhileResponseRunsInBackgroundAndClosesWhenResponseFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
        ).close()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var turn: AgentConversationOverlayTurn
            lateinit var controller: AgentConversationOverlayController
            scenario.onActivity { activity ->
                val runtimeField = MainActivity::class.java.getDeclaredField("agentRuntime").apply {
                    isAccessible = true
                }
                val runtime = runtimeField.get(activity) as KcodeAgentRuntime
                controller = requireNotNull(runtime.conversationOverlayController)
                turn = runBlocking {
                    controller.startTurn(
                        listOf(
                            ChatMessage(
                                id = 1L,
                                role = MessageRole.Assistant,
                                content = "",
                                toolUses = listOf(
                                    ToolUseInfo(
                                        id = "overlay-probe",
                                        name = "overlay_probe",
                                        input = "{}",
                                        status = ToolUseStatus.Succeeded,
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }

            device.pressHome()
            assertNotNull(waitForOverlayTitle(device))
            assertNotNull(device.wait(Until.findObject(By.text("Overlay Probe")), TIMEOUT_MILLIS))
            assertOverlayWindowConfiguration(context, controller)

            runBlocking {
                turn.update(
                    listOf(ChatMessage(1L, MessageRole.Assistant, "**Markdown response updated**")),
                )
            }
            assertOverlayUpdateState(controller)

            runBlocking { turn.finish() }
            device.wait(Until.gone(By.text("kcode · Current conversation")), TIMEOUT_MILLIS / 2)
            device.wait(Until.gone(By.text("kcode · 当前会话")), TIMEOUT_MILLIS / 2)
            assertNull(findOverlayTitle(device))
        }
    }

    private fun waitForOverlayTitle(device: UiDevice) =
        device.wait(Until.findObject(By.text("kcode · Current conversation")), TIMEOUT_MILLIS / 2)
            ?: device.wait(Until.findObject(By.text("kcode · 当前会话")), TIMEOUT_MILLIS / 2)

    private fun findOverlayTitle(device: UiDevice) =
        device.findObject(By.text("kcode · Current conversation")) ?: device.findObject(By.text("kcode · 当前会话"))

    private fun assertOverlayWindowConfiguration(
        context: Context,
        controller: AgentConversationOverlayController,
    ) {
        val paramsField = controller.javaClass.getDeclaredField("layoutParams").apply {
            isAccessible = true
        }
        val params = requireNotNull(paramsField.get(controller) as? WindowManager.LayoutParams)
        val windowManager = context.getSystemService(WindowManager::class.java)
        val density = context.resources.displayMetrics.density
        val fullOverlayHeight = minOf(
            (420 * density).toInt(),
            windowManager.currentWindowMetrics.bounds.height() - (120 * density).toInt(),
        )

        assertEquals(fullOverlayHeight / 4, params.height)
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
        assertEquals((28 * density).toInt(), params.blurBehindRadius)
    }

    private fun assertOverlayUpdateState(controller: AgentConversationOverlayController) {
        val messagesField = controller.javaClass.getDeclaredField("messagesById").apply { isAccessible = true }
        val messages = messagesField.get(controller) as Map<*, *>
        assertEquals("**Markdown response updated**", (messages.values.single() as ChatMessage).content)

        val viewField = controller.javaClass.getDeclaredField("overlayView").apply { isAccessible = true }
        val view = requireNotNull(viewField.get(controller) as? androidx.compose.ui.platform.ComposeView)
        assertTrue("Overlay composition must remain active", view.hasComposition)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
