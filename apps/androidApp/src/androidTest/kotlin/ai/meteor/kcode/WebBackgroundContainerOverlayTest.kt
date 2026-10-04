package ai.meteor.kcode

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebBackgroundContainerOverlayTest {
    @Test(timeout = 60_000)
    fun dragsRestoresAndClosesBackgroundContainerFromFloatingWindow() {
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
                background.click()

                val floatingWindow = waitForAnyDescription(
                    device,
                    "Show background Web containers",
                    "查看后台 Web 容器",
                )
                val initialCenter = floatingWindow.visibleCenter
                assertTrue(initialCenter.x > device.displayWidth * 0.75f)
                assertTrue(kotlin.math.abs(initialCenter.y - device.displayHeight / 2) < 160)

                assertTrue(
                    device.drag(
                        initialCenter.x,
                        initialCenter.y,
                        initialCenter.x - 120,
                        initialCenter.y + 140,
                        20,
                    ),
                )
                val movedWindow = waitForAnyDescription(
                    device,
                    "Show background Web containers",
                    "查看后台 Web 容器",
                )
                val movedCenter = movedWindow.visibleCenter
                assertTrue(movedCenter.x < initialCenter.x - 60)
                assertTrue(movedCenter.y > initialCenter.y + 60)
                movedWindow.click()
                waitForAnyDescription(
                    device,
                    "Collapse background Web containers",
                    "收起后台 Web 容器",
                ).click()
                val restoredWindow = waitForAnyDescription(
                    device,
                    "Show background Web containers",
                    "查看后台 Web 容器",
                )
                assertTrue(kotlin.math.abs(restoredWindow.visibleCenter.x - movedCenter.x) < 30)
                assertTrue(kotlin.math.abs(restoredWindow.visibleCenter.y - movedCenter.y) < 30)
                restoredWindow.click()
                waitForAnyDescription(
                    device,
                    "Bring $title to foreground",
                    "将 $title 切换到前台",
                ).click()

                waitForAnyDescription(device, "Move container to background", "将容器移到后台").click()
                waitForAnyDescription(device, "Show background Web containers", "查看后台 Web 容器").click()
                waitForAnyDescription(device, "Close $title", "关闭 $title").click()

                device.wait(Until.gone(By.text(title)), TIMEOUT_MILLIS)
                assertTrue(runBlocking { controller.list().isEmpty() })
            }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun waitForAnyDescription(device: UiDevice, vararg descriptions: String) =
        device.wait(Until.findObject(By.desc(Pattern.compile(
            descriptions.joinToString("|") { Pattern.quote(it) },
        ))), TIMEOUT_MILLIS) ?: error("Could not find any of: ${descriptions.joinToString()}")

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
