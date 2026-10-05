package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json

class GLMModelAdapterTest {
    @Test
    fun catalogAndFactoryHaveTheSameOwner() {
        val adapter = nativeGLMAdapter()
        val catalog = requireNotNull(adapter.catalog)
        assertEquals(ModelProvider.GLM, catalog.provider)
        assertTrue(catalog.models.isNotEmpty())
        assertTrue(catalog.models.all { it.provider == catalog.provider })
        assertTrue(catalog.displayNames["en"].orEmpty().isNotBlank())
        assertTrue(catalog.displayNames["zh"].orEmpty().isNotBlank())
        assertTrue(adapter.supports(ModelConfiguration(catalog.provider, catalog.models.first().id, "fixture", 0.6)))
        assertTrue(!adapter.supports(ModelConfiguration(ModelProvider("external.fixture"), "fixture", "fixture", 0.6)))
    }

}

private object ClientCreated : RuntimeException()
