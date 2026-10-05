package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import org.cordis.dependencies
import org.cordis.plugin

fun settingsSectionPlugin(section: SettingsSection): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.ui.settings.${section.id}", "builtin", "built-in", setOf("uiSlots", "settings.section")),
    plugin<Unit>(name = "settings-${section.id}", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
        collect(ctx.require(KcodeUiSlots.Key).registerSettings(section))
    },
    Unit,
)
