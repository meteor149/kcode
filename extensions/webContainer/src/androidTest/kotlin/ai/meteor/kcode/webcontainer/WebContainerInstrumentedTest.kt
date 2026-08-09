package ai.meteor.kcode.webcontainer

import android.content.Intent
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class WebContainerInstrumentedTest {
    @Test
    fun mapsHtmlJavascriptAndRelativeAssetsFromWorkspace() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.filesDir, "agent_workspace/instrumented-preview").apply { mkdirs() }
        File(directory, "index.html").writeText(
            """
            <!doctype html><html><head><link rel="stylesheet" href="style.css"></head>
            <body><p id="status">waiting</p><script src="app.js"></script></body></html>
            """.trimIndent(),
        )
        File(directory, "style.css").writeText("body { color: rgb(62, 118, 83); }")
        File(directory, "app.js").writeText("document.querySelector('#status').textContent='ready'")

        val handler = WebContainerActivity.WorkspacePathHandler(context)
        val html = handler.handle("instrumented-preview/index.html")
        val css = handler.handle("instrumented-preview/style.css")
        val javascript = handler.handle("instrumented-preview/app.js")

        assertTrue(html?.data?.bufferedReader()?.readText()?.contains("app.js") == true)
        assertEquals("body { color: rgb(62, 118, 83); }", css?.data?.bufferedReader()?.readText())
        assertTrue(javascript?.data?.bufferedReader()?.readText()?.contains("textContent='ready'") == true)
        assertTrue(javascript?.mimeType?.contains("javascript") == true)
    }

    @Test
    fun configuresWebViewForStandardBrowserApis() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entry = File(context.filesDir, "agent_workspace/instrumented-standard-api/index.html")
        entry.parentFile?.mkdirs()
        entry.writeText(
            "<!doctype html><html><body><button id='debug-button' onclick=\"console.log('button-clicked')\">Run</button></body></html>",
        )
        val containerId = "instrumented-test"
        AndroidWebContainerSessions.open(
            containerId,
            WebPreviewRequest("/workspace/instrumented-standard-api/index.html", "Instrumented preview"),
        )
        val intent = Intent(context, WebContainerActivity::class.java).apply {
            putExtra(WebContainerActivity.EXTRA_ENTRY_PATH, "/workspace/instrumented-standard-api/index.html")
            putExtra(WebContainerActivity.EXTRA_CONTAINER_ID, containerId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<WebContainerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.webView.settings.javaScriptEnabled)
                assertTrue(activity.webView.webChromeClient != null)
                assertTrue(
                    activity.findViewByContentDescription(
                        activity.getString(R.string.web_preview_background),
                    ) is ImageButton,
                )
                val screenshot = AndroidWebContainerSessions.screenshot(containerId, activity)
                assertTrue(screenshot.pngBytes.size > 100)
                assertTrue(screenshot.width > 0)
                assertTrue(screenshot.height > 0)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(WebContainerState.Background, AndroidWebContainerSessions.list().single().state)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(WebContainerState.Foreground, AndroidWebContainerSessions.list().single().state)
            val controller = AndroidWebContainerLauncher(context)
            runBlocking {
                delay(200)
                val inspection = controller.inspect(containerId)
                val button = inspection.elements.single { it.name == "Run" }
                controller.interact(
                    WebInteractionRequest(containerId, WebInteractionAction.Click, handle = button.handle),
                )
                delay(100)
                val logs = controller.console(containerId, cursor = 0, limit = 20)
                assertTrue(logs.entries.any { it.message.contains("button-clicked") })
            }
        }
    }

    private fun android.app.Activity.findViewByContentDescription(description: String): android.view.View? {
        fun find(view: android.view.View): android.view.View? {
            if (view.contentDescription == description) return view
            val group = view as? ViewGroup ?: return null
            repeat(group.childCount) { index -> find(group.getChildAt(index))?.let { return it } }
            return null
        }
        return find(window.decorView)
    }

}
