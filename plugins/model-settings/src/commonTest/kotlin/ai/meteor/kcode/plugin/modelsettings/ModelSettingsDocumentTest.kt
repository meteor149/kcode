package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.modelApiKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModelSettingsDocumentTest {
    private fun document(encoded: String) = Json.parseToJsonElement(encoded) as JsonObject

    @Test
    fun absentAndPartialHistoricalFieldsUseFeatureDefaultsButExplicitEmptyValuesRemain() {
        assertEquals(ModelSettingsValues(), ModelSettingsDocument.read(StoredAppSettings()))
        val partial = StoredAppSettings(legacyValues = document("""{"provider":"private.gateway","future":null}"""))
        assertEquals(ModelSettingsValues(provider = "private.gateway"), ModelSettingsDocument.read(partial))
        val explicit = StoredAppSettings(legacyValues = document("""{"provider":"","modelId":"","temperature":0}"""))
        assertEquals(ModelSettingsValues(provider = "", modelId = "", temperature = 0.0), ModelSettingsDocument.read(explicit))
        assertFailsWith<IllegalArgumentException> {
            ModelSettingsDocument.read(StoredAppSettings(legacyValues = document("""{"provider":null}""")))
        }
    }

    @Test
    fun emptyNamespaceUsesFeatureDefaultsAndNeverResurrectsLegacySecrets() {
        val stored = LegacySettings(provider = "legacy.provider", modelId = "legacy-model",
            modelApiKeys = mapOf("OpenAI" to "legacy-secret"), temperature = 0.2,
            namespaces = mapOf("feature.model-settings" to JsonObject(emptyMap())))
        assertEquals(ModelSettingsValues(), ModelSettingsDocument.read(stored))
        val written = ModelSettingsDocument.write(stored, ModelSettingsDocument.read(stored))
        assertEquals(emptyMap(), ModelSettingsDocument.read(written).modelApiKeys)
        assertEquals(stored.modelApiKeys, written.modelApiKeys)
        assertEquals(stored.provider, written.provider)
    }

    @Test
    fun ownedFieldTypesAreStrictWhileUnknownFieldsRemainOpaque() {
        for (encoded in listOf("""{"provider":7}""", """{"modelId":null}""",
            """{"modelApiKeys":{"custom":false}}""", """{"temperature":"0.5"}""")) {
            assertFailsWith<IllegalArgumentException> {
                ModelSettingsDocument.read(StoredAppSettings(namespaces = mapOf("feature.model-settings" to document(encoded))))
            }
        }
        val future = document("""{"future":[null,{"key.with.dots":false}]}""")
        val stored = StoredAppSettings(namespaces = mapOf("feature.model-settings" to future))
        val saved = ModelSettingsDocument.write(stored, ModelSettingsDocument.read(stored))
        assertEquals(future["future"], saved.namespaces["feature.model-settings"]!!["future"])
    }

    @Test
    fun commandsPreserveUnknownProvidersAndNamespacesWhileClearingOnlyTheSelectedCredential() {
        val provider = ModelProvider("private.gateway:v2")
        val catalog = ModelCatalogSnapshot(listOf(ModelProviderSpec(provider,
            listOf(ModelOption(provider, "private-model", defaultTemperature = 0.6)), 0)))
        val future = document("""{"future":null}""")
        val stored = LegacySettings(modelApiKeys = mapOf(provider.id to "legacy-secret"), namespaces = mapOf(
            "disabled.feature" to future,
            "feature.model-settings" to document("""{"provider":"private.gateway:v2","modelId":"private-model","modelApiKeys":{"private.gateway:v2":"new-secret","offline.provider":" keep "},"temperature":0.6,"future":[null,{}]}"""),
        ))
        val saved = stored.applyModelSettingsUpdate(SettingsUpdate(mapOf("model-api-key" to "")), catalog).settings
        val values = ModelSettingsDocument.read(saved)
        assertEquals(provider.id, values.provider)
        assertEquals(mapOf("offline.provider" to " keep "), values.modelApiKeys)
        assertEquals(future, saved.namespaces["disabled.feature"])
        assertEquals(stored.namespaces["feature.model-settings"]!!["future"], saved.namespaces["feature.model-settings"]!!["future"])
        assertEquals(stored.modelApiKeys, saved.modelApiKeys)
        assertNull(CatalogModelSettingsPolicy(TemperatureRange(0.0, 1.0)).resolve(saved, ModelCatalogSnapshot()))
        assertEquals(provider.id, ModelSettingsDocument.read(saved).provider)
    }
}
