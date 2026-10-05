package ai.meteor.kcode

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real native host and privately packaged UI without changing saved settings. */
@RunWith(AndroidJUnit4::class)
class DefaultUiSmokeTest {
    @Test(timeout = 180_000)
    fun conversationSettingsFormsAndActivityRecreationRenderNormally() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }

        fun shell(command: String) {
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
            assertTrue(output, !output.contains("Exception"))
        }
        fun tap(element: UiObject2) {
            device.waitForIdle(1_000)
            val description = element.contentDescription
            val label = element.text
            fun match(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if ((description != null && description == node.contentDescription?.toString()) ||
                    (label != null && label == node.text?.toString())) return node
                repeat(node.childCount) { index ->
                    node.getChild(index)?.let { child -> match(child)?.let { return it } }
                }
                return null
            }
            // Exercise Android's real accessibility click action. Vendor input
            // injection policies must not turn a missed synthetic touch into a UI failure.
            var target = instrumentation.uiAutomation.rootInActiveWindow?.let(::match)
            while (target != null && !target.isClickable) target = target.parent
            assertNotNull("Missing clickable control: ${description ?: label}", target)
            assertTrue("Control rejected click: ${description ?: label}",
                target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }

        fun find(selector: BySelector, label: String): UiObject2 {
            val element = device.wait(Until.findObject(selector), 45_000)
            if (element == null) {
                device.takeScreenshot(File(evidence, "failure.png"))
                device.dumpWindowHierarchy(File(evidence, "failure.xml"))
            }
            assertNotNull("Missing $label", element)
            return element!!
        }
        fun text(vararg labels: String) = By.text(Pattern.compile(labels.joinToString("|") { Pattern.quote(it) }))
        fun description(vararg labels: String) = By.desc(Pattern.compile(labels.joinToString("|") { Pattern.quote(it) }))

        fun scrollTo(selector: BySelector, label: String): UiObject2 {
            repeat(8) {
                device.findObject(selector)?.let { return it }
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    repeat(node.childCount) { index ->
                        node.getChild(index)?.let { if (scroll(it)) return true }
                    }
                    return false
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::scroll)
                device.waitForIdle(1_000)
            }
            return find(selector, label)
        }

        fun activity(): MainActivity? {
            var result: MainActivity? = null
            instrumentation.runOnMainSync {
                result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
            }
            return result
        }
        // Start as the shell so vendor restrictions on background app launches
        // do not prevent the instrumentation process from opening the real host.
        val component = ComponentName(instrumentation.targetContext, MainActivity::class.java).flattenToString()
        shell("input keyevent KEYCODE_WAKEUP")
        shell("am start -W -n $component")
        try {
            val menu = description("Open sidebar", "打开侧栏")
            val launchDeadline = System.nanoTime() + 45_000_000_000L
            while (System.nanoTime() < launchDeadline && device.findObject(menu) == null) {
                device.findObject(text("Don't allow", "Don’t allow", "不允许", "拒绝"))?.let(::tap)
                if (device.findObject(description("Open settings", "打开设置")) != null) {
                    device.findObject(text("Chats", "对话"))?.let(::tap)
                }
                Thread.sleep(100)
            }
            if (device.findObject(description("Open settings", "打开设置")) != null) {
                tap(find(text("Chats", "对话"), "return from sidebar to chat"))
            }
            find(menu, "conversation menu")
            assertTrue(device.takeScreenshot(File(evidence, "conversation.png")))
            tap(find(description("More", "更多"), "home more button"))
            find(text("More", "更多"), "more menu heading")
            assertTrue(device.takeScreenshot(File(evidence, "more.png")))
            tap(find(text("Export", "导出"), "registered export action"))
            find(text("Save to photos", "保存到相册"), "export save choice")
            find(text("Share image", "分享图片"), "export share choice")
            assertTrue(device.takeScreenshot(File(evidence, "export.png")))
            shell("input keyevent KEYCODE_BACK")
            tap(find(menu, "conversation sidebar"))
            tap(find(description("Open settings", "打开设置"), "sidebar settings action"))
            find(text("Model service", "模型服务"), "model settings section")
            find(text("Language", "界面语言"), "language settings section")
            assertTrue(device.takeScreenshot(File(evidence, "settings.png")))

            tap(find(text("Model service", "模型服务"), "model settings section"))
            find(text("Provider", "供应商"), "model provider form")
            tap(scrollTo(text("Alibaba DashScope", "阿里云 DashScope"), "independent Alibaba provider"))
            scrollTo(text("China mainland", "中国大陆"), "provider-owned mainland region")
            scrollTo(text("Singapore", "新加坡"), "provider-owned Singapore region")
            scrollTo(text("United States", "美国"), "provider-owned US region")
            assertTrue(device.takeScreenshot(File(evidence, "model-regions.png")))
            shell("input keyevent KEYCODE_BACK")
            find(text("Language", "界面语言"), "settings home after returning")

            tap(find(text("Tool permission", "Shell 权限"), "shell settings section"))
            find(By.text(Pattern.compile(".*(?:ADB|Shizuku).*")), "shell execution choices")
            shell("input keyevent KEYCODE_BACK")
            find(text("Model service", "模型服务"), "settings home after shell form")
            shell("input keyevent KEYCODE_BACK")
            tap(find(text("Chats", "对话"), "sidebar conversation destination after closing settings"))
            find(menu, "conversation after closing settings")
            val beforeRecreation = requireNotNull(activity()) { "MainActivity is not resumed" }
            instrumentation.runOnMainSync { beforeRecreation.recreate() }
            val recreationDeadline = System.nanoTime() + 45_000_000_000L
            var recreatedActivity: MainActivity? = null
            while (System.nanoTime() < recreationDeadline) {
                val resumed = activity()
                if (resumed != null && resumed !== beforeRecreation) {
                    recreatedActivity = resumed
                    break
                }
                Thread.sleep(50)
            }
            assertNotNull("Activity must be recreated", recreatedActivity)
            find(menu, "conversation after activity recreation")
            find(description("More", "更多"), "more button after activity recreation")
            assertTrue(device.takeScreenshot(File(evidence, "recreated.png")))
            tap(find(menu, "conversation menu"))
            tap(find(description("Open settings", "打开设置"), "sidebar settings action"))
            find(text("Model service", "模型服务"), "settings from sidebar")
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
        }
    }
}
