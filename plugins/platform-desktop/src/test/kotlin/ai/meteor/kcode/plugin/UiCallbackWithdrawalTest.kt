package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.mountUiSlots
import ai.meteor.kcode.ui.component.KcodeIconAsset
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class UiCallbackWithdrawalTest {
    @Test
    fun capturedSettingsActionsRejectWorkAfterTheirSectionIsWithdrawn() = runBlocking {
        val context = Context()
        val slots = mountUiSlots(context)
        var captured: SettingsSectionRequest? = null
        var submitted = 0
        var returned = 0
        var dismissed = 0
        try {
            val registration = slots.registerSettings(SettingsSection(
                "fixture", 0, KcodeIconAsset.Settings, { "Fixture" }, { "" },
                UiRenderer { captured = it },
            ))
            val held = slots.snapshot().settingsSections.single()
            withContext(Dispatchers.Main.immediate) {
                val scene = ImageComposeScene(width = 20, height = 20, coroutineContext = coroutineContext) {
                    held.renderer.Render(SettingsSectionRequest(SettingsPageRequest(
                        appSettings = StoredAppSettings(),
                        persistenceFailure = null,
                        onSettingsChange = { submitted++ },
                        onDismiss = { dismissed++ },
                    )) { returned++ })
                }
                try {
                    scene.render()
                    val actions = assertNotNull(captured)
                    actions.page.onSettingsChange(StoredAppSettings())
                    actions.onReturn()
                    actions.page.onDismiss()
                    registration.dispose()
                    assertFailsWith<IllegalStateException> { actions.page.onSettingsChange(StoredAppSettings()) }
                    assertFailsWith<IllegalStateException> { actions.onReturn() }
                    assertFailsWith<IllegalStateException> { actions.page.onDismiss() }
                    assertEquals(1, submitted)
                    assertEquals(1, returned)
                    assertEquals(1, dismissed)
                    scene.render()
                } finally { scene.close() }
            }
        } finally { context.fiber.dispose() }
    }
}
