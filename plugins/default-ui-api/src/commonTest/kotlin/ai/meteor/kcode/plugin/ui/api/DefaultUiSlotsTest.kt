package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.plugin.api.UiSlotKey
import ai.meteor.kcode.ui.component.KcodeIconAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DefaultUiSlotsTest {
    @Test
    fun textDefaultsWithdrawIndependentlyAndOldCleanupKeepsReplacement() = runTest {
        val context = Context()
        val slots = KcodeUiSlots(context)
        try {
            val source = UiTextDictionary("feature", mapOf("feature.label" to "Feature"))
            val first = slots.registerTexts(source)
            val held = slots.snapshot().textDictionaries.single()
            assertFailsWith<IllegalArgumentException> {
                slots.registerTexts(UiTextDictionary("conflict", mapOf("feature.label" to "Other")))
            }
            val independent = slots.registerTexts(UiTextDictionary("other", mapOf("other.label" to "Other")))
            first.dispose()
            assertEquals(false, held.available.value)
            val replacement = slots.registerTexts(source)
            first.dispose()
            assertEquals(listOf("feature", "other"), slots.snapshot().textDictionaries.map { it.id })
            assertEquals(true, slots.snapshot().textDictionaries.first().available.value)
            replacement.dispose()
            independent.dispose()
            assertEquals(emptyList(), slots.snapshot().textDictionaries)
        } finally { context.fiber.dispose() }
    }

    @Test
    fun failedSettingsRegistrationReleasesItsNewTextDefaults() = runTest {
        val context = Context()
        val slots = KcodeUiSlots(context)
        val section = SettingsSection("fixture", 0, KcodeIconAsset.Settings, { "" }, { "" }, UiRenderer { })
        try {
            val original = slots.registerSettings(section)
            assertFailsWith<IllegalArgumentException> {
                slots.registerSettings(section.copy(texts = mapOf("fixture.label" to "Fixture")))
            }
            assertEquals(emptyList(), slots.snapshot().textDictionaries)
            assertEquals(1, slots.snapshot().settingsSections.size)
            original.dispose()
            val replacement = slots.registerSettings(section.copy(texts = mapOf("fixture.label" to "Fixture")))
            val held = slots.snapshot().textDictionaries.single()
            replacement.dispose()
            assertEquals(false, held.available.value)
            assertEquals(emptyList(), slots.snapshot().textDictionaries)
        } finally { context.fiber.dispose() }
    }

    @Test
    fun pluginFiberDisposalWithdrawsItsSlotsAndFeatureContributions() = runTest {
        val context = Context()
        val registry = KcodeUiSlots(context)
        val key = UiSlotKey<String>("feature.plugin.content")
        try {
            val provider = context.plugin(
                plugin<Unit>(name = "fixture.ui", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
                    val slots = ctx.require(KcodeUiSlots.Key)
                    collect(slots.register(key, "content"))
                    collect(slots.registerEffect(ApplicationEffect("fixture", 0, UiRenderer { })))
                },
                Unit,
            ).await()
            assertEquals("content", registry.resolve(key))
            assertEquals(listOf("fixture"), registry.snapshot().effects.map { it.id })
            provider.dispose()
            assertNull(registry.resolve(key))
            assertEquals(emptyList(), registry.snapshot().effects)
        } finally {
            context.fiber.dispose()
        }
    }

    @Test
    fun slotsUseContractIdsAndOldDisposersPreserveReusedRendererInstances() = runTest {
        val context = Context()
        val registry = KcodeUiSlots(context)
        val renderer = UiRenderer<ChatPageRequest> { }
        try {
            val key = UiSlotKey<UiRenderer<ChatPageRequest>>(ApplicationSlots.Chat.id)
            val original = registry.register(key, renderer)
            assertSame(renderer, registry.resolve(ApplicationSlots.Chat))
            assertSame(renderer, registry.snapshot().chat)
            assertFailsWith<IllegalArgumentException> { registry.register(ApplicationSlots.Chat, renderer) }
            val captured = registry.snapshot()
            original.dispose()
            assertNull(registry.resolve(key))
            val replacement = registry.register(ApplicationSlots.Chat, renderer)
            original.dispose()
            assertSame(renderer, registry.snapshot().chat)
            assertSame(renderer, captured.chat)
            replacement.dispose()
            assertNull(registry.snapshot().chat)

            val custom = UiSlotKey<String>("feature.custom.content")
            val contribution = registry.register(custom, "content")
            assertEquals("content", registry.resolve(UiSlotKey<String>(custom.id)))
            contribution.dispose()
            assertNull(registry.resolve(custom))
        } finally {
            context.fiber.dispose()
        }
    }

    @Test
    fun everyContributionKindProtectsReplacementIdentityAndWithdrawsExactlyOnce() = runTest {
        val context = Context()
        val registry = KcodeUiSlots(context)
        suspend fun verify(register: suspend () -> Disposable, read: suspend () -> List<Any>) {
            val original = register()
            assertEquals(1, read().size)
            assertFailsWith<IllegalArgumentException> { register() }
            original.dispose()
            assertEquals(emptyList(), read())
            val replacement = register()
            original.dispose()
            assertEquals(1, read().size)
            replacement.dispose()
            replacement.dispose()
            assertEquals(emptyList(), read())
        }
        try {
            val section = SettingsSection("shared", 0, KcodeIconAsset.Settings, { "title" }, { "description" }, UiRenderer { })
            verify({ registry.registerSettings(section) }, { registry.snapshot().settingsSections })
            val message = MessagePresentation("shared", 0, { true }, UiRenderer { })
            verify({ registry.registerMessage(message) }, { registry.snapshot().messagePresentations })
            val tool = ToolUsePresentation("shared", 0, { true }, UiRenderer { })
            verify({ registry.registerToolUse(tool) }, { registry.snapshot().toolUsePresentations })
            val effect = ApplicationEffect("shared", 0, UiRenderer { })
            verify({ registry.registerEffect(effect) }, { registry.snapshot().effects })
            val destination = NavigationDestination("shared", 0, KcodeIconAsset.Chat, { "title" }, UiRenderer { })
            verify({ registry.registerNavigation(destination) }, { registry.snapshot().navigation })
            val decoration = ConversationDecoration("shared", 0, ConversationDecorationPresenter { emptyList() })
            verify({ registry.registerConversationDecoration(decoration) }, { registry.snapshot().conversationDecorations })
            val conversationEffect = ConversationPageEffect("shared", 0, UiRenderer { })
            verify({ registry.registerConversationEffect(conversationEffect) }, { registry.snapshot().conversationEffects })
        } finally {
            context.fiber.dispose()
        }
    }

    @Test
    fun orderedContributionsAreDetachedAndDifferentKindsCanShareIds() = runTest {
        val context = Context()
        val registry = KcodeUiSlots(context)
        try {
            val registrations = listOf(
                registry.registerEffect(ApplicationEffect("z", 10, UiRenderer { })),
                registry.registerEffect(ApplicationEffect("b", 0, UiRenderer { })),
                registry.registerEffect(ApplicationEffect("a", 0, UiRenderer { })),
                registry.registerMessage(MessagePresentation("a", 0, { true }, UiRenderer { })),
            )
            val captured = registry.snapshot()
            assertEquals(listOf("a", "b", "z"), captured.effects.map { it.id })
            assertEquals(listOf("a"), captured.messagePresentations.map { it.id })
            registrations.forEach { it.dispose() }
            assertEquals(emptyList(), registry.snapshot().effects)
            assertEquals(listOf("a", "b", "z"), captured.effects.map { it.id })
        } finally {
            context.fiber.dispose()
        }
    }

    @Test
    fun cancellationDoesNotPreventRegistrationCleanup() = runTest {
        val context = Context()
        val registry = KcodeUiSlots(context)
        val key = UiSlotKey<String>("feature.cancelled")
        try {
            val registration = registry.register(key, "value")
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    registration.dispose()
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertNull(registry.resolve(key))
        } finally {
            context.fiber.dispose()
        }
    }
}
