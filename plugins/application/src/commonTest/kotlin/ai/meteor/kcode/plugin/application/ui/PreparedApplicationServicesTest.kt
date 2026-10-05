package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.chat.ConversationCommandSnapshot
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.KcodeConversationCommands
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.UiContributionsSnapshot
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.DefaultUiSnapshotKey
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.NavigationDestination
import ai.meteor.kcode.plugin.ui.api.NavigationPagePresenter
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.test.runTest
import org.cordis.Context
import org.cordis.ServiceKey
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PreparedApplicationServicesTest {
    @Test
    fun preparingNewPageCapabilitiesRetainsTheNavigationCompositionIdentity() = runTest {
        val context = Context()
        val settings = KcodeSettings(context, object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = StoredAppSettings()
            override suspend fun save(settings: StoredAppSettings) = Unit
        })
        var preparations = 0
        val destination = NavigationDestination(
            "feature", 0, KcodeIconAsset.Chat, { "Feature" }, UiRenderer { },
            presenter = NavigationPagePresenter {
                val preparation = ++preparations
                UiRenderer { check(preparation > 0) }
            },
        )
        val slots = ApplicationUiSlots(navigation = listOf(destination))
        val services = object : ApplicationServices {
            override val uiContributions = UiContributionsSnapshot(mapOf(DefaultUiSnapshotKey.id to slots))
            override val modelCatalog = ModelCatalogSnapshot()
            override val conversationCommands = ConversationCommandSnapshot()
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(key: ServiceKey<T>): T? = if (key == KcodeSettings.Key) settings as T else null
        }
        try {
            val first = assertNotNull(defaultApplicationServices(services)).uiSlots.navigation.single()
            val second = assertNotNull(defaultApplicationServices(services)).uiSlots.navigation.single()
            assertNotSame(first.renderer, second.renderer)
            assertSame(destination.renderKey, first.renderKey)
            assertSame(first.renderKey, second.renderKey)
        } finally { context.fiber.dispose() }
    }

    @Test
    fun committedFrameReusesPreparedProjectionsWithoutReadingTheirProvidersAgain() = runTest {
        val context = Context()
        val settings = KcodeSettings(context, object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = StoredAppSettings()
            override suspend fun save(settings: StoredAppSettings) = Unit
        })
        val slots = ApplicationUiSlots()
        val catalog = ModelCatalogSnapshot()
        val commands = ConversationCommandSnapshot()
        val services = object : ApplicationServices {
            override val uiContributions = UiContributionsSnapshot(mapOf(DefaultUiSnapshotKey.id to slots))
            override val modelCatalog = catalog
            override val conversationCommands = commands

            @Suppress("UNCHECKED_CAST")
            override fun <T> get(key: ServiceKey<T>): T? {
                check(key != KcodeUiSlots.Key && key != KcodeLlm.Key && key != KcodeConversationCommands.Key) {
                    "Prepared providers must not be queried again"
                }
                return if (key == KcodeSettings.Key) settings as T else null
            }
        }
        try {
            val prepared = assertNotNull(defaultApplicationServices(services))
            assertSame(catalog, prepared.modelCatalog)
            assertSame(commands, prepared.commands)
            assertSame(slots, prepared.uiSlots)
        } finally { context.fiber.dispose() }
    }
}
