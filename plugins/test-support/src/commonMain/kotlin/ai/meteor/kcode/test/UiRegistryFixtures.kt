package ai.meteor.kcode.test

import ai.meteor.kcode.plugin.UiContributionsServicePlugin
import ai.meteor.kcode.plugin.UiSlotsServicePlugin
import ai.meteor.kcode.plugin.api.KcodeUiContributions
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import org.cordis.Context
import org.cordis.Plugin

fun defaultUiRegistryProvider(): Plugin<Unit> = UiSlotsServicePlugin

suspend fun mountUiContributions(context: Context): KcodeUiContributions {
    context[KcodeUiContributions.Key]?.let { return it }
    context.plugin(UiContributionsServicePlugin, Unit).await()
    return context.require(KcodeUiContributions.Key)
}

suspend fun mountUiSlots(context: Context): KcodeUiSlots {
    mountUiContributions(context)
    context.plugin(UiSlotsServicePlugin, Unit).await()
    return context.require(KcodeUiSlots.Key)
}
