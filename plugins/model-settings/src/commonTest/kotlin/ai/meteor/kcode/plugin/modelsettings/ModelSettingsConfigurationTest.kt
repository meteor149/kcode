package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModelSettingsConfigurationTest {
    @Test
    fun declaredConnectionFieldsAndFiniteTemperatureAreRequired() {
        val provider = ModelProvider("private.gateway")
        val catalog = ModelCatalogSnapshot(listOf(ModelProviderSpec(
            provider,
            listOf(ModelOption(provider, "private-model", defaultTemperature = 0.6)),
            0,
            requirements = ModelConnectionRequirements(endpoint = true, region = true, deployment = true),
        )))
        val settings = StoredAppSettings(
            provider = provider.id,
            modelId = "private-model",
            modelApiKeys = mapOf(provider.id to "fixture"),
            modelEndpoint = "https://example.invalid",
            modelRegion = "private-region",
            modelDeployment = "deployment",
            temperature = 1.75,
        )
        val policy = CatalogModelSettingsPolicy(resolveTemperatureRange(Json.parseToJsonElement(
            """{"minimumTemperature":0.5,"maximumTemperature":2.0}""",
        )))
        assertEquals(1.75, policy.resolve(settings, catalog)?.temperature)
        assertEquals(0.5, policy.resolve(settings.copy(temperature = -1.0), catalog)?.temperature)
        for (missing in listOf(
            settings.copy(modelApiKeys = emptyMap()),
            settings.copy(modelEndpoint = ""),
            settings.copy(modelRegion = ""),
            settings.copy(modelDeployment = ""),
            settings.copy(temperature = Double.NaN),
            settings.copy(temperature = Double.POSITIVE_INFINITY),
        )) {
            assertNull(policy.resolve(missing, catalog))
        }
        val configuration = checkNotNull(policy.resolve(settings, catalog))
        policy.close()
        assertFailsWith<IllegalStateException> { policy.resolve(settings, catalog) }
        assertFailsWith<IllegalStateException> { policy.update(settings, configuration) }
    }

    @Test
    fun invalidPolicyConfigurationIsRejectedBeforeMount() {
        for (raw in listOf(
            """{"unknown":1}""",
            """{"minimumTemperature":"0.5"}""",
            """{"maximumTemperature":null}""",
            """{"maximumTemperature":3}""",
            """{"minimumTemperature":-1}""",
            """{"minimumTemperature":1,"maximumTemperature":0.5}""",
            "[]",
        )) {
            assertFailsWith<Exception> { resolveTemperatureRange(Json.parseToJsonElement(raw)) }
        }
        assertEquals(TemperatureRange(0.0, 1.0), resolveTemperatureRange(Unit))
    }
}
