package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.test.defaultUiRegistryProvider
import ai.meteor.kcode.test.mountUiContributions
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.api.UiSlotKey
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class LocalizationFeatureLifecycleTest {
    @Test
    fun headlessDictionarySurvivesUiWithdrawalAndFeatureDisposalRemovesAllOwnedUi() = runTest {
        val context = Context()
        try {
            val feature = context.plugin(LocalizationFeaturePlugin, Unit).await()
            val catalog = context.require(KcodeLocalization.Key).catalog
            assertEquals("New chat", catalog.translate(AppLanguage.English, UiText.NewChat))
            mountUiContributions(context)
            val uiPlugin = defaultUiRegistryProvider()
            suspend fun awaitContributions(slots: KcodeUiSlots) = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (slots.snapshot().localization == null ||
                        slots.snapshot().settingsSections.none { it.id == "language" }) {
                        delay(1)
                    }
                }
            }
            val first = context.plugin(uiPlugin, Unit).await()
            awaitContributions(context.require(KcodeUiSlots.Key))
            assertSame(catalog, context.require(KcodeUiSlots.Key).snapshot().localization)
            first.dispose()
            assertEquals("New chat", catalog.translate(AppLanguage.English, UiText.NewChat))
            val restored = context.plugin(uiPlugin, Unit).await()
            val slots = context.require(KcodeUiSlots.Key)
            awaitContributions(slots)
            assertEquals(1, slots.snapshot().settingsSections.count { it.id == "language" })
            val independentSlot = UiSlotKey<String>("test.independent-setting-page")
            val independent = slots.register(independentSlot, "Available")
            feature.dispose()
            assertNull(context[KcodeLocalization.Key])
            assertNull(slots.snapshot().localization)
            assertEquals(emptyList(), slots.snapshot().settingsSections)
            assertEquals("Available", slots.resolve(independentSlot))
            assertFailsWith<IllegalStateException> { catalog.translate(AppLanguage.English, UiText.NewChat) }
            independent.dispose()
            restored.dispose()
        } finally { context.fiber.dispose() }
    }
}
