package ai.meteor.kcode.plugin.markdown

import ai.meteor.kcode.test.defaultUiRegistryProvider
import ai.meteor.kcode.test.mountUiContributions
import ai.meteor.kcode.plugin.api.UiSlotKey
import ai.meteor.kcode.plugin.ui.api.KcodeMarkdown
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import kotlinx.coroutines.test.runTest
import org.cordis.Context
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MarkdownFeatureLifecycleTest {
    @Test
    fun formattingSurvivesUiWithdrawalAndFeatureDisposalRemovesOnlyItsProjection() = runTest {
        val context = Context()
        try {
            val feature = context.plugin(MarkdownFeaturePlugin, Unit).await()
            val content = context.require(KcodeMarkdown.Key).content
            assertEquals("A bold result", content.plainText("A **bold** result"))
            mountUiContributions(context)
            val uiPlugin = defaultUiRegistryProvider()
            suspend fun awaitProjection(slots: KcodeUiSlots) {
                var observed = context.registry.values().flatMap { it.fibers.snapshot() }
                while (true) {
                    observed.forEach { it.await() }
                    val current = context.registry.values().flatMap { it.fibers.snapshot() }
                    if (current.toSet() == observed.toSet()) break
                    observed = current
                }
                assertSame(content, slots.snapshot().markdown)
            }
            val first = context.plugin(uiPlugin, Unit).await()
            awaitProjection(context.require(KcodeUiSlots.Key))
            assertSame(content, context.require(KcodeUiSlots.Key).snapshot().markdown)
            first.dispose()
            assertEquals("A bold result", content.plainText("A **bold** result"))
            val restored = context.plugin(uiPlugin, Unit).await()
            val slots = context.require(KcodeUiSlots.Key)
            awaitProjection(slots)
            assertSame(content, slots.snapshot().markdown)
            val independentSlot = UiSlotKey<String>("test.independent-content")
            val independent = slots.register(independentSlot, "Available")
            feature.dispose()
            assertNull(context[KcodeMarkdown.Key])
            assertNull(slots.snapshot().markdown)
            assertEquals("Available", slots.resolve(independentSlot))
            assertFailsWith<IllegalStateException> { content.plainText("stale") }
            independent.dispose()
            restored.dispose()
        } finally { context.fiber.dispose() }
    }
}
