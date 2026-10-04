package ai.meteor.kcode.plugin.webcontainer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebContainerInstrumentedTest {
    @Test
    fun mapsHtmlJavascriptAndRelativeAssetsFromWorkspace() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "agent_workspace/instrumented-preview").apply { mkdirs() }
        File(directory, "index.html").writeText(
            """
            <!doctype html><html><head><link rel="stylesheet" href="style.css"></head>
            <body><p id="status">waiting</p><script src="app.js"></script></body></html>
            """.trimIndent(),
        )
        File(directory, "style.css").writeText("body { color: rgb(62, 118, 83); }")
        File(directory, "app.js").writeText("document.querySelector('#status').textContent='ready'")

        val handler = AndroidWebContainerContent.WorkspacePathHandler(context)
        try {
            val html = handler.handle("instrumented-preview/index.html")
            val css = handler.handle("instrumented-preview/style.css")
            val javascript = handler.handle("instrumented-preview/app.js")

            assertTrue(html?.data?.bufferedReader()?.readText()?.contains("app.js") == true)
            assertEquals("body { color: rgb(62, 118, 83); }", css?.data?.bufferedReader()?.readText())
            assertTrue(javascript?.data?.bufferedReader()?.readText()?.contains("textContent='ready'") == true)
            assertTrue(javascript?.mimeType?.contains("javascript") == true)
            val pending = requireNotNull(handler.handle("instrumented-preview/app.js")).data
            handler.close()
            handler.close()
            assertThrows(IOException::class.java) { pending.read() }
            assertNull(handler.handle("instrumented-preview/index.html"))
        } finally {
            handler.close()
            directory.walkBottomUp().forEach { it.delete() }
        }
    }

}
