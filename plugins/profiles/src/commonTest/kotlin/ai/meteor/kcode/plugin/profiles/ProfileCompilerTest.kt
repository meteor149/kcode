package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileCompilerTest {
    @Test
    fun contextEditsReplaceOnlyPresentFieldsAndReportTheirLayer() {
        val profile = ProfileDefinition(id = "context", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("entry", "example", inject = mapOf("answer" to JsonPrimitive(true)),
                isolate = mapOf("answer" to null)))),
            ProfileOperation.Context("entry", inject = emptyMap(), isolate = mapOf("answer" to "shared")),
        ))
        val result = compiler.compile(profile, emptyList()).requireValid()
        val entry = result.entries.single()
        assertEquals(emptyMap(), entry.inject)
        assertEquals(org.cordis.loader.IsolationRule.Shared("shared"), entry.isolate?.get("answer"))
        assertEquals("profile:context", result.origins["entry"]?.get("isolate")?.layer)
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(profile.copy(patches = profile.patches + ProfileOperation.Context("entry", isolate = mapOf("answer" to ""))), emptyList())
        }
    }

    @Test
    fun persistedExplicitNullRemainsDifferentFromOmittedConfiguration() {
        val definition = ProfileDefinition(id = "nullable", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("omitted", "module"), ProfileEntry("explicit", "module", JsonNull),
        ))))
        val restored = Json.decodeFromString<ProfileDefinition>(Json.encodeToString(definition))
        assertEquals(definition, restored)
        val entries = compiler.compile(restored, emptyList()).requireValid().entries
        assertEquals(null, profileConfiguration(entries[0]))
        assertEquals(JsonNull, profileConfiguration(entries[1])?.decode())
    }

    @Test
    fun machineAddressesApplyToEveryInstanceWithoutChangingPortableIntent() {
        val definition = ProfileDefinition(id = "custom", patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("first", "storage"), ProfileEntry("second", "storage"),
        ))))
        val machine = profileMachineConfiguration(definition, emptyList(), mapOf(
            "storage" to ai.meteor.kcode.plugin.api.StoredPluginConfiguration.encode("/local/data"),
        ))
        val tree = compiler.compile(definition, emptyList(), machine).requireValid().entries
        assertEquals(listOf("/local/data", "/local/data"), tree.map { profileConfiguration(it)?.decode() })
        assertEquals(null, (definition.patches.single() as ProfileOperation.Insert).entries.first().config)
        assertEquals("profile-custom", profileDataScopeKey("custom", "profile"))
        assertEquals("shared-team", profileDataScopeKey("custom", "team"))
    }

    @Test
    fun serviceMetadataUsesNativeContextValuesAndPreservesRealmRules() {
        val entry = ProfileEntry("consumer", "example.consumer",
            inject = mapOf("answer" to JsonPrimitive(true)),
            intercept = mapOf("answer" to JsonPrimitive("configured")),
            isolate = mapOf("answer" to null, "shared" to "workspace"),
        )
        val result = compiler.compile(ProfileDefinition(id = "test", patches = listOf(
            ProfileOperation.Insert(listOf(entry)),
        )), emptyList()).requireValid().entries.single()
        assertEquals(true, result.inject?.get("answer"))
        assertEquals("configured", result.intercept?.get("answer"))
        assertEquals(org.cordis.loader.IsolationRule.Local, result.isolate?.get("answer"))
        assertEquals(org.cordis.loader.IsolationRule.Shared("workspace"), result.isolate?.get("shared"))
    }

    private val compiler = ProfileCompiler()
    private val bundle = ProfileBundle(1, "base", "1", listOf(
        ProfileOperation.Insert(listOf(ProfileEntry("agent", "agent.koog", JsonPrimitive("default")))),
    ))

    @Test
    fun profileRoundTripPreservesNullAndOperationTypes() {
        val profile = ProfileDefinition(
            id = "coding",
            bundles = listOf(ProfileBundleReference("base", "1")),
            patches = listOf(ProfileOperation.Configure("agent", JsonNull), ProfileOperation.Disable("agent")),
        )
        assertEquals(profile, Json.decodeFromString<ProfileDefinition>(Json.encodeToString(profile)))
    }

    @Test
    fun allSurfacesUseTheSameOrderedComposition() {
        val profile = ProfileDefinition(
            id = "coding",
            bundles = listOf(ProfileBundleReference("base", "1")),
            patches = listOf(ProfileOperation.Configure("agent", JsonPrimitive("profile"))),
        )
        val result = compiler.compile(profile, listOf(bundle),
            machineOverrides = listOf(ProfileOperation.Configure("agent", JsonPrimitive("machine"))),
            launchOverrides = listOf(ProfileOperation.Configure("agent", JsonPrimitive("launch"))),
        ).requireValid()
        assertEquals(JsonPrimitive("launch"), result.entries.single().config)
        assertEquals("launch", result.origins["agent"]?.get("config")?.layer)
        val default = compiler.compile(profile.copy(patches = emptyList()), listOf(bundle)).requireValid()
        assertEquals(JsonPrimitive("default"), default.entries.single().config)
    }

    @Test
    fun emptyProfilesAndExplicitReplacementAreSupported() {
        assertTrue(compiler.compile(ProfileDefinition(id = "empty"), emptyList()).entries.isEmpty())
        val profile = ProfileDefinition(1, "coding", bundles = listOf(ProfileBundleReference("base", "1")),
            patches = listOf(ProfileOperation.Replace("agent", "agent.custom", "agent.koog")))
        assertEquals("agent.custom", compiler.compile(profile, listOf(bundle)).requireValid().entries.single().name)
        assertTrue(compiler.compile(profile.copy(patches = listOf(ProfileOperation.Replace("agent", "agent.custom", "wrong"))), listOf(bundle)).diagnostics.isNotEmpty())
    }

    @Test
    fun missingBundleAndPathLikeScopeAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(ProfileDefinition(id = "coding", bundles = listOf(ProfileBundleReference("missing", "1"))), emptyList())
        }
        assertFailsWith<IllegalArgumentException> { ProfileDefinition(id = "../escape").validate() }
        assertFailsWith<IllegalArgumentException> { ProfileDataScope(workspace = "C:/workspace").validate() }
    }
}
