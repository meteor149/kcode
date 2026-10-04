package ai.meteor.kcode.tools.search

/** Provider-neutral search vocabulary. No HTTP, model tool, or credential types cross this seam. */
interface WebSearchBackend {
    suspend fun search(query: String, maxResults: Int): WebSearchResponse
    fun close() = Unit
}

data class WebSearchResponse(val provider: String, val results: List<WebSearchResult>)

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedDate: String? = null,
)
