package ai.meteor.kcode.plugin.searchhttp

import ai.meteor.kcode.tools.search.WebSearchBackend
import ai.meteor.kcode.tools.search.WebSearchResponse
import ai.meteor.kcode.tools.search.WebSearchResult
import kotlinx.coroutines.Job
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.decodeURLQueryComponent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** HTTP implementation; it owns and closes its client when the provider Fiber is disposed. */
class HttpWebSearchBackend(
    private val configurationProvider: suspend () -> WebSearchConfiguration,
    private val engine: HttpClientEngine = createWebSearchEngine(),
) : WebSearchBackend {
    private val client = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(SearchJson) }
    }

    override suspend fun search(query: String, maxResults: Int): WebSearchResponse {
        require(query.isNotBlank() && query.length <= MAX_QUERY_LENGTH) { "Invalid search query" }
        require(maxResults in 1..MAX_RESULTS) { "Invalid search result limit" }
        val configuration = configurationProvider()
        val results = when (configuration.provider) {
            WebSearchProvider.Google -> searchGoogle(query, maxResults)
            WebSearchProvider.Exa -> searchExa(query, maxResults, configuration.exaApiKey)
            WebSearchProvider.BrightData -> searchBrightData(query, maxResults, configuration.brightDataApiKey)
        }
        return WebSearchResponse(configuration.provider.name, results)
    }

    override fun close() {
        try { client.close() } finally { engine.close() }
    }

    /** The constructor receives an explicitly owned engine; HttpClient(engine) does not own it. */
    suspend fun closeAndJoin() {
        close()
        client.coroutineContext[Job]?.join()
        engine.coroutineContext[Job]?.join()
    }

    private suspend fun searchBrightData(query: String, limit: Int, rawApiKey: String): List<WebSearchResult> {
        val apiKey = requireApiKey(rawApiKey, "Bright Data")
        val googleUrl = URLBuilder(GOOGLE_SEARCH_URL).apply {
            parameters.append("brd_json", "1")
            parameters.append("q", query)
            parameters.append("num", limit.toString())
        }.buildString()
        return client.post(BRIGHT_DATA_ENDPOINT) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(BrightDataRequest(url = googleUrl))
        }.body<BrightDataSearchResponse>().organic.map {
            WebSearchResult(it.title, it.link, it.description.orEmpty())
        }
    }

    private suspend fun searchExa(query: String, limit: Int, rawApiKey: String): List<WebSearchResult> {
        val apiKey = requireApiKey(rawApiKey, "Exa")
        return client.post(EXA_SEARCH_ENDPOINT) {
            header("x-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(ExaSearchRequest(query = query, numResults = limit))
        }.body<ExaSearchResponse>().results.map {
            WebSearchResult(it.title.orEmpty().ifBlank { it.url }, it.url, it.highlights.firstOrNull().orEmpty(), it.publishedDate)
        }
    }

    private suspend fun searchGoogle(query: String, limit: Int): List<WebSearchResult> {
        val html = client.get(GOOGLE_SEARCH_URL) {
            header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
            url {
                parameters.append("q", query)
                parameters.append("num", limit.coerceAtMost(10).toString())
                parameters.append("hl", "zh-CN")
                parameters.append("filter", "0")
            }
        }.bodyAsText()
        if (html.contains("/sorry/", ignoreCase = true) || html.contains("unusual traffic", ignoreCase = true)) {
            error("Google temporarily requires CAPTCHA. Switch search engine or try again later.")
        }
        val results = GOOGLE_RESULT_PATTERN.findAll(html).mapNotNull { match ->
            val rawUrl = decodeHtml(match.groupValues[1])
            val url = normalizeGoogleUrl(rawUrl) ?: return@mapNotNull null
            val title = stripHtml(match.groupValues[2])
            if (title.isBlank()) return@mapNotNull null
            val following = html.substring(match.range.last + 1, minOf(html.length, match.range.last + 1 + GOOGLE_SNIPPET_WINDOW))
            WebSearchResult(title, url, extractGoogleSnippet(following))
        }.distinctBy(WebSearchResult::url).take(limit).toList()
        if (results.isEmpty() && (html.contains("consent.google", true) || html.contains("Before you continue", true))) {
            error("Google requires a consent page in this region. Switch search engine or retry on another network.")
        }
        return if (results.isNotEmpty()) results else searchGoogleNews(query, limit)
    }

    private suspend fun searchGoogleNews(query: String, limit: Int): List<WebSearchResult> {
        val xml = client.get(GOOGLE_NEWS_RSS_URL) {
            header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            url {
                parameters.append("q", query)
                parameters.append("hl", "zh-CN")
                parameters.append("gl", "CN")
                parameters.append("ceid", "CN:zh-Hans")
            }
        }.bodyAsText()
        return RSS_ITEM_PATTERN.findAll(xml).mapNotNull { itemMatch ->
            val item = itemMatch.groupValues[1]
            val title = RSS_TITLE_PATTERN.find(item)?.groupValues?.get(1)?.let(::decodeHtml)?.clean().orEmpty()
            val url = RSS_LINK_PATTERN.find(item)?.groupValues?.get(1)?.trim().orEmpty()
            if (title.isBlank() || !url.startsWith("http")) return@mapNotNull null
            val snippet = RSS_DESCRIPTION_PATTERN.find(item)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
            val date = RSS_DATE_PATTERN.find(item)?.groupValues?.get(1)?.trim()
            WebSearchResult(title, url, snippet, date)
        }.take(limit).toList()
    }

    private fun normalizeGoogleUrl(raw: String): String? {
        val value = if (raw.startsWith("/url?")) {
            raw.substringAfter("q=", "").substringBefore('&').decodeURLQueryComponent()
        } else raw
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        if (value.contains("google.com/search") || value.contains("accounts.google.")) return null
        return value
    }

    private fun extractGoogleSnippet(html: String): String {
        val plain = stripHtml(html)
        return plain.substringBefore("Cached").substringBefore("Translate").clean()
    }

    private fun stripHtml(value: String): String = decodeHtml(value.replace(TAG_PATTERN, " ")).clean()

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun requireApiKey(value: String, provider: String): String = value.trim().also {
        require(it.isNotEmpty()) { "$provider search is not configured. Add its API key in Settings > Internet search." }
    }

    private fun String.clean(): String = replace(Regex("\\s+"), " ").trim()

    @Serializable private data class BrightDataRequest(val zone: String = "serp_api1", val url: String, val format: String = "raw")
    @Serializable private data class BrightDataSearchResponse(val organic: List<BrightDataResult> = emptyList())
    @Serializable private data class BrightDataResult(val link: String, val title: String, val description: String? = null)
    @Serializable private data class ExaSearchRequest(
        val query: String,
        val type: String = "auto",
        val numResults: Int,
        val contents: ExaContents = ExaContents(),
    )
    @Serializable private data class ExaContents(val highlights: ExaHighlights = ExaHighlights())
    @Serializable private class ExaHighlights
    @Serializable private data class ExaSearchResponse(val results: List<ExaResult> = emptyList())
    @Serializable private data class ExaResult(
        val title: String? = null,
        val url: String,
        val publishedDate: String? = null,
        val highlights: List<String> = emptyList(),
    )

    private companion object {
        const val BRIGHT_DATA_ENDPOINT = "https://api.brightdata.com/request"
        const val EXA_SEARCH_ENDPOINT = "https://api.exa.ai/search"
        const val GOOGLE_SEARCH_URL = "https://www.google.com/search"
        const val GOOGLE_NEWS_RSS_URL = "https://news.google.com/rss/search"
        const val MAX_QUERY_LENGTH = 500
        const val MAX_RESULTS = 10
        const val GOOGLE_SNIPPET_WINDOW = 1_200
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        val SearchJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val GOOGLE_RESULT_PATTERN = Regex("""(?is)<a[^>]+href=["']([^"']+)["'][^>]*>\s*<h3[^>]*>(.*?)</h3>""")
        val TAG_PATTERN = Regex("<[^>]+>")
        val RSS_ITEM_PATTERN = Regex("(?is)<item>(.*?)</item>")
        val RSS_TITLE_PATTERN = Regex("(?is)<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>")
        val RSS_LINK_PATTERN = Regex("(?is)<link>(.*?)</link>")
        val RSS_DESCRIPTION_PATTERN = Regex("(?is)<description>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</description>")
        val RSS_DATE_PATTERN = Regex("(?is)<pubDate>(.*?)</pubDate>")
    }
}

internal expect fun createWebSearchEngine(): HttpClientEngine
