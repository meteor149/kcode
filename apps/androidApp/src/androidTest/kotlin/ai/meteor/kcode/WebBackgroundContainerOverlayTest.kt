package ai.meteor.kcode

import ai.meteor.kcode.webcontainer.AndroidWebContainerLauncher
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebBackgroundContainerOverlayTest {
    @Test
    fun dragsRestoresAndClosesBackgroundContainerFromFloatingWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val title = "Floating preview test"
        val entry = File(context.filesDir, "agent_workspace/floating-preview/index.html")
        entry.parentFile?.mkdirs()
        entry.writeText("<!doctype html><html><body><h1>Floating preview</h1></body></html>")
        val controller = AndroidWebContainerLauncher(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking { controller.launch(WebPreviewRequest("/workspace/floating-preview/index.html", title)) }
            val background = waitForAnyDescription(
                device,
                "Move preview to background",
                "将预览移到后台",
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

            waitForAnyDescription(device, "Move preview to background", "将预览移到后台").click()
            waitForAnyDescription(device, "Show background Web containers", "查看后台 Web 容器").click()
            waitForAnyDescription(device, "Close $title", "关闭 $title").click()

            device.wait(Until.gone(By.text(title)), TIMEOUT_MILLIS)
            assertTrue(runBlocking { controller.list().isEmpty() })
        }
    }

    private fun waitForAnyDescription(device: UiDevice, vararg descriptions: String) =
        descriptions.firstNotNullOfOrNull { description ->
            device.wait(Until.findObject(By.desc(description)), TIMEOUT_MILLIS / descriptions.size)
        } ?: error("Could not find any of: ${descriptions.joinToString()}")

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
