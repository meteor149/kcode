package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.DynamicPluginSpec
import ai.meteor.kcode.plugin.api.StoredDynamicPlugin
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilePortableExportTest {
    @Test
    fun rawLocalDescriptorsCannotClaimAPortableVerifiedRecipe() {
        val bundle = ProfileBundle(id = "base", version = "1", patches = emptyList())
        val raw = DynamicPluginSpec("legacy", "1", "fixture.Legacy", "machine.jar", "a".repeat(64))
        val generation = source(bundle).copy(composition = PluginCompositionSnapshot(
            external = listOf(StoredDynamicPlugin.from(raw)),
        ))
        assertFailsWith<IllegalArgumentException> { ProfilePortableExporter().export(generation) }
    }

    @Test
    fun defaultPolicyRejectsOverriddenBundleSecretsWithoutPrintingTheirValues() {
        val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("provider", "example.provider", JsonPrimitive("credential-sentinel")))),
        ))
        val source = source(bundle, listOf(ProfileOperation.Configure("provider", JsonPrimitive("harmless"))))
        val error = assertFailsWith<IllegalArgumentException> { ProfilePortableExporter().export(source) }
        assertFalse(error.message.orEmpty().contains("credential-sentinel"))
        assertTrue(error.message.orEmpty().contains("configuration review"))
    }

    @Test
    fun schemaReviewTransformsEveryStoredLayerAndDoesNotMutateTheSource() {
        val secret = JsonPrimitive("credential-sentinel")
        val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry(
                "group", "core.group", children = listOf(ProfileEntry(
                    "provider", "example.provider", secret,
                    inject = mapOf("dependency" to secret), intercept = mapOf("service" to secret),
                )),
            ))),
        ))
        val source = source(bundle, listOf(
            ProfileOperation.Configure("provider", secret),
            ProfileOperation.Context("provider", inject = mapOf("dependency" to secret)),
        ))
        val locations = mutableListOf<String>()
        val exporter = ProfilePortableExporter { location, field, _ ->
            locations += "$location/$field"
            JsonPrimitive("portable")
        }
        val text = exporter.export(source)
        val document = ProfilePortableExporter.decode(text)
        assertEquals(5, locations.size)
        assertTrue(locations.any { it.contains("children/0/config") })
        assertTrue(locations.any { it.contains("intercept/service") })
        assertFalse(text.contains("credential-sentinel"))
        assertEquals(secret, (source.definition.patches.first() as ProfileOperation.Configure).config)
        assertEquals(ProfileDataScope(workspace = "profile"), document.definition.dataScope)
        assertEquals("shared-settings", source.definition.dataScope.settings)
        assertEquals("1", document.bundles.single().version)
        assertEquals(source.lock, document.lock)
    }

    @Test
    fun unconfiguredCompositionExportsWithoutAReviewAndRetainsContextRealms() {
        val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry(
                "provider", "example.provider", isolate = mapOf("service" to "shared"),
            ))),
        ))
        val document = ProfilePortableExporter.decode(ProfilePortableExporter().export(source(bundle)))
        assertEquals(bundle, document.bundles.single())
        assertEquals(listOf(ProfileBundleReference("base", "1")), document.definition.bundles)
    }

    @Test
    fun explicitNullAndContextOverridesStillRequireReview() {
        val bundle = ProfileBundle(id = "base", version = "1", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("provider", "example.provider"))),
        ))
        for (patch in listOf(
            ProfileOperation.Configure("provider", JsonNull),
            ProfileOperation.Context("provider", intercept = mapOf("service" to JsonNull)),
        )) {
            assertFailsWith<IllegalArgumentException> { ProfilePortableExporter().export(source(bundle, listOf(patch))) }
        }
    }

    @Test
    fun decodeRejectsUnsupportedFormatsUnknownFieldsAndMissingFrozenBundles() {
        val bundle = ProfileBundle(id = "base", version = "1", patches = emptyList())
        val text = ProfilePortableExporter().export(source(bundle))
        assertFailsWith<IllegalArgumentException> {
            ProfilePortableExporter.decode(text.replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 9"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProfilePortableExporter.decode(text.replaceFirst("{", "{\"artifactPath\":\"machine\","))
        }
        assertFailsWith<IllegalArgumentException> {
            PortableProfileDocument(definition = source(bundle).definition, bundles = emptyList(), lock = ProfileLock()).validate()
        }
    }

    private fun source(bundle: ProfileBundle, patches: List<ProfileOperation> = emptyList()) = CommittedProfileGeneration(
        generation = 1,
        definition = ProfileDefinition(
            id = "coding", bundles = listOf(ProfileBundleReference(bundle.id, bundle.version)), patches = patches,
            dataScope = ProfileDataScope("shared-settings", "shared-history", "shared-workspace"),
        ),
        lock = ProfileLock(),
        composition = PluginCompositionSnapshot(),
        bundles = listOf(bundle),
    )
}
