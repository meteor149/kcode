package ai.meteor.kcode.h5

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
class H5ContainerInstrumentedTest {
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

        val handler = H5ContainerActivity.WorkspacePathHandler(context)
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
        AndroidH5Sessions.open(
            containerId,
            H5PreviewRequest("/workspace/instrumented-standard-api/index.html", "Instrumented preview"),
        )
        val intent = Intent(context, H5ContainerActivity::class.java).apply {
            putExtra(H5ContainerActivity.EXTRA_ENTRY_PATH, "/workspace/instrumented-standard-api/index.html")
            putExtra(H5ContainerActivity.EXTRA_CONTAINER_ID, containerId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<H5ContainerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.webView.settings.javaScriptEnabled)
                assertTrue(activity.webView.webChromeClient != null)
                assertTrue(
                    activity.findViewByContentDescription(
                        activity.getString(R.string.h5_preview_background),
                    ) is ImageButton,
                )
                val screenshot = AndroidH5Sessions.screenshot(containerId, activity)
                assertTrue(screenshot.pngBytes.size > 100)
                assertTrue(screenshot.width > 0)
                assertTrue(screenshot.height > 0)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(H5ContainerState.Background, AndroidH5Sessions.list().single().state)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(H5ContainerState.Foreground, AndroidH5Sessions.list().single().state)
            val controller = AndroidH5ContainerLauncher(context)
            runBlocking {
                delay(200)
                val inspection = controller.inspect(containerId)
                val button = inspection.elements.single { it.name == "Run" }
                controller.interact(
                    H5InteractionRequest(containerId, H5InteractionAction.Click, handle = button.handle),
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
