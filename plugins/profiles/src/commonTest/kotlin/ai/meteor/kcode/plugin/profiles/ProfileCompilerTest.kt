package ai.meteor.kcode.plugin.profiles

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
