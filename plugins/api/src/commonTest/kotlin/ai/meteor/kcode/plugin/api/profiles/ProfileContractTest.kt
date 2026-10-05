package ai.meteor.kcode.plugin.api.profiles

import ai.meteor.kcode.platform.PluginHostApiPackages
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileContractTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun omittedConfigurationAndExplicitNullHaveDistinctWireMeaningAndCompleteDescriptors() {
        val json = Json { encodeDefaults = true }
        val absent = ProfileEntry("entry", "package")
        val explicit = absent.copy(config = JsonNull)
        val encodedAbsent = json.encodeToString(ProfileEntry.serializer(), absent)
        val encodedExplicit = json.encodeToString(ProfileEntry.serializer(), explicit)
        assertTrue(!encodedAbsent.contains("\"config\""))
        assertTrue(encodedExplicit.contains("\"config\":null"))
        assertNull(json.decodeFromString(ProfileEntry.serializer(), encodedAbsent).config)
        assertEquals(JsonNull, json.decodeFromString(ProfileEntry.serializer(), encodedExplicit).config)
        val descriptor = ProfileEntry.serializer().descriptor
        assertEquals("ai.meteor.kcode.plugin.api.profiles.ProfileEntry", descriptor.serialName)
        assertTrue(descriptor.getElementIndex("config") >= 0)
        assertTrue(descriptor.isElementOptional(descriptor.getElementIndex("config")))
    }

    @Test
    fun contextChangesRoundTripAndAllPortableTypesUseTheSharedSdkNamespace() {
        val definition = ProfileDefinition(id = "coding", patches = listOf(
            ProfileOperation.Context("group", inject = emptyMap(), intercept = mapOf("answer" to JsonPrimitive(false)),
                isolate = mapOf("answer" to "realm")),
        ))
        assertEquals(definition, Json.decodeFromString(ProfileDefinition.serializer(), Json.encodeToString(ProfileDefinition.serializer(), definition)))
        assertTrue("ai.meteor.kcode.plugin.api" in PluginHostApiPackages)
        assertEquals(emptyMap(), (definition.patches.single() as ProfileOperation.Context).inject)
    }
}
