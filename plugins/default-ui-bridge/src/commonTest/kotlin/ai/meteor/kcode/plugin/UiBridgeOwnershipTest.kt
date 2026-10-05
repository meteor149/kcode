package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeUiContributions
import ai.meteor.kcode.plugin.api.UiContributionSource
import ai.meteor.kcode.plugin.api.UiSlotKey
import ai.meteor.kcode.plugin.ui.api.DefaultUiSnapshotKey
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import kotlinx.coroutines.test.runTest
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UiBridgeOwnershipTest {
    @Test
    fun defaultBridgeCanWithdrawWithoutWithdrawingAlternativeRootContributions() = runTest {
        val context = Context()
        try {
            val bridge = context.plugin(UiSlotsServicePlugin, Unit).await()
            assertNull(context[KcodeUiSlots.Key])
            val neutral = context.plugin(UiContributionsServicePlugin, Unit).await()
            bridge.await()
            val contributions = context.require(KcodeUiContributions.Key)
            val custom = UiSlotKey<String>("test.alternative-root")
            val registration = contributions.registerProjection(custom, UiContributionSource { "alternative" })
            assertNotNull(context[KcodeUiSlots.Key])
            assertNotNull(contributions.snapshot(DefaultUiSnapshotKey))
            bridge.dispose()
            assertNull(context[KcodeUiSlots.Key])
            assertNull(contributions.snapshot(DefaultUiSnapshotKey))
            assertEquals("alternative", contributions.snapshot(custom))
            registration.dispose()
            neutral.dispose()
            assertNull(context[KcodeUiContributions.Key])
        } finally { context.fiber.dispose() }
    }
}
