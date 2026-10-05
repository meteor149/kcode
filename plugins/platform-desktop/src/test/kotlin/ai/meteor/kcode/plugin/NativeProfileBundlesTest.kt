package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeProfileBundlesTest {
    @Test
    fun explicitLayersPreserveCatalogueOrderAndAllowPlatformSubsets() {
        val bundles = nativeProfileBundles(listOf(
            "provider.ui.theme", "provider.fs.platform", "core.tools", "provider.ui.compose",
            "provider.shell.ubuntu", "provider.settings.platform", "provider.llm.koog.DeepSeek",
        ))
        assertEquals(listOf("kcode.base", "kcode.agent", "kcode.default-ui"), bundles.map { it.id })
        assertEquals(listOf("core.tools", "provider.settings.platform"), entries(bundles[0]))
        assertEquals(listOf("provider.fs.platform", "provider.shell.ubuntu", "provider.llm.koog.DeepSeek"), entries(bundles[1]))
        assertEquals(listOf("provider.ui.theme", "provider.ui.compose"), entries(bundles[2]))
        val withoutUi = nativeProfileTemplate(bundles).copy(bundles = nativeProfileTemplate(bundles).bundles.dropLast(1))
        assertEquals(entries(bundles[0]) + entries(bundles[1]),
            ProfileCompiler().compile(withoutUi, bundles).requireValid().entries.map { it.id })
    }

    @Test
    fun undeclaredModulesCannotBeActivatedByTheirNames() {
        listOf("core.example", "provider.ui.example", "provider.llm.koog.Example", "example.plugin").forEach { id ->
            val error = assertFailsWith<IllegalArgumentException> { nativeProfileBundles(listOf(id)) }
            assertTrue(error.message.orEmpty().contains(id))
        }
        assertFailsWith<IllegalArgumentException> { nativeProfileBundles(listOf("core.tools", "core.tools")) }
        assertFailsWith<IllegalArgumentException> { nativeProfileBundles(listOf("")) }
    }

    @Test
    fun emptyDistributionAndTemplateSelectionDoNotInventInstances() {
        val bundles = nativeProfileBundles(emptyList())
        assertTrue(bundles.all { entries(it).isEmpty() })
        val definition = nativeProfileTemplate(bundles, includeDefaults = false)
        assertTrue(definition.bundles.isEmpty())
        assertTrue(ProfileCompiler().compile(definition, bundles).requireValid().entries.isEmpty())
    }

    private fun entries(bundle: ai.meteor.kcode.plugin.api.profiles.ProfileBundle): List<String> =
        (bundle.patches.single() as ProfileOperation.Insert).entries.map { it.id }
}
