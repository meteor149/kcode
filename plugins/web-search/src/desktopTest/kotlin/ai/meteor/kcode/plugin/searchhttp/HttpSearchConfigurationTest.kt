package ai.meteor.kcode.plugin.searchhttp

import ai.meteor.kcode.tools.search.SearchSettingsConfiguration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HttpSearchConfigurationTest {
    @Test
    fun unsupportedRoutesCannotBeExecutedByTheHttpTransport() {
        assertFailsWith<IllegalArgumentException> {
            SearchSettingsConfiguration("custom.route", emptyMap()).httpSearchConfiguration()
        }
    }
}
