package ai.meteor.kcode

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import android.content.Context
import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebOwnedWindowFlowTest {
    @Test(timeout = 60_000)
    fun dragsRestoresAndClosesThroughTheApplicationWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val title = "Floating preview test"
        val entry = File(context.filesDir, "agent_workspace/floating-preview/index.html")
        entry.parentFile?.mkdirs()
        entry.writeText("<!doctype html><html><body><h1>Floating preview</h1></body></html>")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val controller = runBlocking {
                    withTimeout(10_000) {
                        var active: WebContainerController? = null
                        while (active == null) {
                            scenario.onActivity { activity ->
                                val field = MainActivity::class.java.getDeclaredField("agentRuntime").apply { isAccessible = true }
                                active = (field.get(activity) as? KcodeAgentRuntime)?.webContainerController
                            }
                            if (active == null) delay(50)
                        }
                        requireNotNull(active)
                    }
                }
                runBlocking { controller.launch(WebPreviewRequest("/workspace/floating-preview/index.html", title)) }
                val background = waitForAnyDescription(
                    device,
                    "Move container to background",
                    "将容器移到后台",
                )
                clickOwnWindow(context, background)

                val floatingWindow = waitForAnyDescription(
                    device,
                    "Show background Web containers",
                    "查看后台 Web 容器",
                )
                val initialCenter = floatingWindow.visibleCenter
                assertTrue(initialCenter.x > device.displayWidth * 0.75f)
                assertTrue(kotlin.math.abs(initialCenter.y - device.displayHeight / 2) < 160)

                dragOwnWindow(context, initialCenter.x, initialCenter.y, initialCenter.x - 120, initialCenter.y + 140)
                val movedWindow = waitForAnyDescription(
                    device,
                    "Show background Web containers",
                    "查看后台 Web 容器",
                )
                val movedCenter = movedWindow.visibleCenter
                assertTrue(movedCenter.x < initialCenter.x - 60)
                assertTrue(movedCenter.y > initialCenter.y + 60)
                clickOwnWindow(context, movedWindow)
                clickOwnWindow(context, waitForAnyDescription(
                    device,
                    "Collapse background Web containers",
                    "收起后台 Web 容器",
                ))
                val restoredWindow = waitForAnyDescription(
                    device,
                    "Show background Web containers",
                    "查看后台 Web 容器",
                )
                assertTrue(kotlin.math.abs(restoredWindow.visibleCenter.x - movedCenter.x) < 30)
                assertTrue(kotlin.math.abs(restoredWindow.visibleCenter.y - movedCenter.y) < 30)
                clickOwnWindow(context, restoredWindow)
                clickOwnWindow(context, waitForAnyDescription(
                    device,
                    "Bring $title to foreground",
                    "将 $title 切换到前台",
                ))

                clickOwnWindow(context, waitForAnyDescription(device, "Move container to background", "将容器移到后台"))
                clickOwnWindow(context, waitForAnyDescription(device, "Show background Web containers", "查看后台 Web 容器"))
                clickOwnWindow(context, waitForAnyDescription(device, "Close $title", "关闭 $title"))

                device.wait(Until.gone(By.text(title)), TIMEOUT_MILLIS)
                assertTrue(runBlocking { controller.list().isEmpty() })
            }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    // Dispatch only to this application's own Window. This does not inject OS-wide input.
    private fun clickOwnWindow(context: Context, node: UiObject2) {
        waitForOwnWindowFocus(context)
        Thread.sleep(150)
        val center = node.visibleCenter
        dragOwnWindow(context, center.x, center.y, center.x, center.y, steps = 1)
    }

    private fun waitForOwnWindowFocus(context: Context): Unit = runBlocking {
        withTimeout(5_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                (context.applicationContext as KcodeApplication).hostActivities.current()?.hasWindowFocus() == true
            }) delay(20)
        }
    }

    private fun dragOwnWindow(context: Context, fromX: Int, fromY: Int, toX: Int, toY: Int, steps: Int = 20): Unit = runBlocking {
        waitForOwnWindowFocus(context)
        val activity = withContext(Dispatchers.Main.immediate) {
            requireNotNull((context.applicationContext as KcodeApplication).hostActivities.current())
        }
        val downTime = SystemClock.uptimeMillis()
        assertTrue(dispatch(activity, downTime, MotionEvent.ACTION_DOWN, fromX.toFloat(), fromY.toFloat()))
        for (step in 1..steps) {
            delay(16)
            val ratio = step.toFloat() / steps
            if (step < steps) dispatch(activity, downTime, MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) * ratio, fromY + (toY - fromY) * ratio)
        }
        dispatch(activity, downTime, MotionEvent.ACTION_UP, toX.toFloat(), toY.toFloat())
        Unit
    }

    private suspend fun dispatch(activity: Activity, downTime: Long, action: Int, screenX: Float, screenY: Float) =
        withContext(Dispatchers.Main.immediate) {
            val location = IntArray(2)
            activity.window.decorView.getLocationOnScreen(location)
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = screenX - location[0]; y = screenY - location[1]; pressure = 1f; size = 1f
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }

    private fun waitForAnyDescription(device: UiDevice, vararg descriptions: String) =
        device.wait(Until.findObject(By.desc(Pattern.compile(
            descriptions.joinToString("|") { Pattern.quote(it) },
        ))), TIMEOUT_MILLIS) ?: error("Could not find any of: ${descriptions.joinToString()}")

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
