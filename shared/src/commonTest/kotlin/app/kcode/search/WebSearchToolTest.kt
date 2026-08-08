package app.kcode.search

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class WebSearchToolTest {
    @Test
    fun rejectsSearchWithoutIndependentApiKey() = runTest {
        val tool = WebSearchTool(
            configurationProvider = { WebSearchConfiguration(WebSearchProvider.BrightData) },
            engine = MockEngine { error("must not request") },
        )

        val error = assertFailsWith<IllegalArgumentException> {
            tool.execute(WebSearchTool.Args("Koog"))
        }

        assertContains(error.message.orEmpty(), "Bright Data")
    }

    @Test
    fun returnsBoundedSourcesWithUrls() = runTest {
        val engine = MockEngine { request ->
            assertContains(request.headers[HttpHeaders.Authorization].orEmpty(), "test-key")
            respond(
                content = """{"organic":[{"link":"https://koog.ai/","title":"Koog","description":"Kotlin AI agents"},{"link":"https://github.com/JetBrains/koog","title":"Source","description":"Official repository"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tool = WebSearchTool(
            configurationProvider = { WebSearchConfiguration(WebSearchProvider.BrightData, brightDataApiKey = "test-key") },
            engine = engine,
        )

        val result = tool.execute(WebSearchTool.Args(query = "Koog framework", maxResults = 1))

        assertContains(result, "https://koog.ai/")
        kotlin.test.assertFalse(result.contains("github.com"))
    }

    @Test
    fun exaUsesSelectedEngineAndReturnsHighlights() = runTest {
        val engine = MockEngine { request ->
            kotlin.test.assertEquals("https://api.exa.ai/search", request.url.toString())
            kotlin.test.assertEquals("exa-key", request.headers["x-api-key"])
            respond(
                content = """{"results":[{"title":"Koog docs","url":"https://docs.koog.ai/","publishedDate":"2026-01-02","highlights":["Official Koog documentation"]}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tool = WebSearchTool(
            configurationProvider = { WebSearchConfiguration(WebSearchProvider.Exa, exaApiKey = "exa-key") },
            engine = engine,
        )

        val result = tool.execute(WebSearchTool.Args("Koog"))

        assertContains(result, "Official Koog documentation")
        assertContains(result, "Published: 2026-01-02")
    }

    @Test
    fun googleNeedsNoKeyAndParsesPublicResults() = runTest {
        val engine = MockEngine {
            respond(
                content = """<html><body><a href="/url?q=https%3A%2F%2Fkoog.ai%2F&amp;sa=U"><h3>Koog AI</h3></a><div>Kotlin framework for AI agents</div></body></html>""",
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
            )
        }
        val tool = WebSearchTool(
            configurationProvider = { WebSearchConfiguration(WebSearchProvider.Google) },
            engine = engine,
        )

        val result = tool.execute(WebSearchTool.Args("Koog"))

        assertContains(result, "https://koog.ai/")
        assertContains(result, "Koog AI")
    }

    @Test
    fun googleFallsBackToNewsRssWhenStaticPageHasNoResults() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "news.google.com") {
                respond(
                    content = """<rss><channel><item><title>Koog release - JetBrains</title><link>https://news.google.com/rss/articles/example</link><pubDate>Wed, 05 Aug 2026 12:00:00 GMT</pubDate><description><![CDATA[Koog ships a new release]]></description></item></channel></rss>""",
                    headers = headersOf(HttpHeaders.ContentType, "application/xml"),
                )
            } else {
                respond("<html><script>enablejs</script></html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
            }
        }
        val tool = WebSearchTool(
            configurationProvider = { WebSearchConfiguration(WebSearchProvider.Google) },
            engine = engine,
        )

        val result = tool.execute(WebSearchTool.Args("Koog release"))

        assertContains(result, "https://news.google.com/rss/articles/example")
        assertContains(result, "Koog ships a new release")
    }

}
