package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileExportPolicyTest {
    private val secret = JsonPrimitive("credential-sentinel")
    private val portable = JsonPrimitive("portable")
    private val insertion = ProfileOperation.Insert(listOf(ProfileEntry("entry", "first", secret, configurationKind = "string")))

    @Test
    fun configuredValuesUseTheirCurrentModuleAndCodecIncludingAfterReplacement() {
        val seen = mutableListOf<ProfileExportValue>()
        val exporter = ProfilePortableExporter { value -> seen += value; portable }
        val source = generation(listOf(insertion, ProfileOperation.Replace("entry", "second"),
            ProfileOperation.Configure("entry", secret, "json")))
        val document = ProfilePortableExporter.decode(exporter.export(source))
        assertEquals(listOf("first", "second", "second"), seen.map { it.packageId })
        assertEquals(listOf("string", "string", "json"), seen.map { it.configurationKind })
        assertEquals(listOf("entry", "entry", "entry"), seen.map { it.entryId })
        assertEquals(seen[0].location, seen[1].location)
        assertEquals(portable, (document.definition.patches.last() as ProfileOperation.Configure).config)
    }

    @Test
    fun replacementRequiresEveryReceivingModuleToApproveInheritedValues() {
        val source = generation(listOf(insertion, ProfileOperation.Replace("entry", "second"),
            ProfileOperation.Configure("entry", portable)))
        val policies = ProfileExportPolicies(mapOf("first" to ProfileExportReview { portable }))
        assertFailsWith<IllegalArgumentException> { ProfilePortableExporter(policies).export(source) }
        assertFailsWith<IllegalArgumentException> {
            ProfilePortableExporter { value -> if (value.packageId == "first") portable else JsonPrimitive("different") }.export(source)
        }
    }

    @Test
    fun contextInheritanceIsReviewedButClearedFieldsDoNotTransfer() {
        val seen = mutableListOf<ProfileExportValue>()
        val source = generation(listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("entry", "first", inject = mapOf("service" to secret), intercept = mapOf("answer" to secret)))),
            ProfileOperation.Context("entry", inject = emptyMap()),
            ProfileOperation.Replace("entry", "second"),
        ))
        ProfilePortableExporter { value -> seen += value; portable }.export(source)
        assertEquals(listOf("inject/service", "intercept/answer", "intercept/answer"), seen.map { it.field })
        assertEquals(listOf("first", "first", "second"), seen.map { it.packageId })
    }

    @Test
    fun removalAndReinsertionDoNotReuseOldConfigurationApproval() {
        val seen = mutableListOf<ProfileExportValue>()
        val source = generation(listOf(
            insertion, ProfileOperation.Remove("entry"),
            ProfileOperation.Insert(listOf(ProfileEntry("entry", "second"))),
            ProfileOperation.Replace("entry", "third"),
        ))
        ProfilePortableExporter { value -> seen += value; portable }.export(source)
        assertEquals(listOf("first"), seen.map { it.packageId })
    }

    @Test
    fun bundleManifestOrderControlsReviewEvenIfFrozenStorageOrderDiffers() {
        val first = ProfileBundle(id = "first-layer", version = "1", patches = listOf(insertion))
        val second = ProfileBundle(id = "second-layer", version = "1", patches = listOf(ProfileOperation.Replace("entry", "second")))
        val source = generation(emptyList()).copy(
            definition = ProfileDefinition(id = "coding", bundles = listOf(
                ProfileBundleReference(first.id, first.version), ProfileBundleReference(second.id, second.version),
            )), bundles = listOf(second, first),
        )
        val seen = mutableListOf<String>()
        val document = ProfilePortableExporter.decode(ProfilePortableExporter { value -> seen += value.packageId; portable }.export(source))
        assertEquals(listOf("first", "second"), seen)
        assertEquals(listOf(first.id, second.id), document.bundles.map { it.id })
    }

    @Test
    fun nestedMovementRetainsValueOriginAndReplacementReviewsTheMovedInstance() {
        val seen = mutableListOf<ProfileExportValue>()
        val source = generation(listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("group", "core.group", children = listOf(
                ProfileEntry("entry", "first", secret),
            )))),
            ProfileOperation.Move("entry"), ProfileOperation.Replace("entry", "second"),
        ))
        ProfilePortableExporter { value -> seen += value; portable }.export(source)
        assertEquals(listOf("first", "second"), seen.map { it.packageId })
        assertTrue(seen.all { it.location.endsWith("children/0") })
    }

    @Test
    fun policyMapIsDetachedAndUnknownModulesStayDenied() {
        val values = mutableMapOf("first" to ProfileExportReview { portable })
        val policies = ProfileExportPolicies(values)
        values["first"] = ProfileExportReview { null }
        values["unknown"] = ProfileExportReview { portable }
        assertEquals(portable, policies.review(ProfileExportValue("first", "entry", "string", "location", "config", secret)))
        assertNull(policies.review(ProfileExportValue("unknown", "entry", "string", "location", "config", secret)))
    }

    @Test
    fun failingPolicyDoesNotLeakConfigurationButCancellationRetainsItsIdentity() {
        val exporter = ProfilePortableExporter { error("policy rejected credential-sentinel") }
        val failure = assertFailsWith<IllegalArgumentException> { exporter.export(generation(listOf(insertion))) }
        assertFalse(failure.message.orEmpty().contains("credential-sentinel"))
        assertNull(failure.cause)
        val cancellation = CancellationException("cancel export")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            ProfilePortableExporter { throw cancellation }.export(generation(listOf(insertion)))
        })
    }

    @Test
    fun reusedMutablePolicyOutputCannotChangePreviouslyReviewedValues() {
        val values = mutableMapOf("label" to portable)
        var calls = 0
        val exporter = ProfilePortableExporter {
            if (calls++ == 0) JsonObject(values) else {
                values["label"] = secret
                JsonObject(mapOf("label" to portable))
            }
        }
        val source = generation(listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("a", "first", secret), ProfileEntry("b", "first", secret),
        ))))
        val text = exporter.export(source)
        assertFalse(text.contains("credential-sentinel"))
        val entries = (ProfilePortableExporter.decode(text).definition.patches.single() as ProfileOperation.Insert).entries
        assertTrue(entries.all { it.config == JsonObject(mapOf("label" to portable)) })
    }

    private fun generation(patches: List<ProfileOperation>) = CommittedProfileGeneration(
        generation = 1, definition = ProfileDefinition(id = "coding", patches = patches),
        lock = ProfileLock(), composition = PluginCompositionSnapshot(),
    )
}
