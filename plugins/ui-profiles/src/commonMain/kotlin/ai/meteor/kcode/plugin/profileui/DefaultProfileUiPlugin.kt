package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.KcodeProfiles
import ai.meteor.kcode.plugin.profileui.resources.Res
import ai.meteor.kcode.plugin.profileui.resources.profile_description
import ai.meteor.kcode.plugin.profileui.resources.profile_title
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.component.KcodeIconAsset
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Optional child waits for management/default UI; legacy and alternative roots remain independent. */
object DefaultProfileUiPlugin : Plugin<Unit> {
    override val name = "ui-profiles"
    override val config = ConfigValidator<Unit> { it }
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val child = ctx.plugin(ProfileSettingsContribution, Unit)
        effect.collect { child.dispose() }
    }
}

private object ProfileSettingsContribution : Plugin<Unit> {
    override val name = "settings-profiles"
    override val inject = dependencies(KcodeProfiles.Key, KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val client = ctx.require(KcodeProfiles.Key).client
        val session = ProfileUiSession(client)
        effect.collect { session.close() }
        effect.collect(ctx.require(KcodeUiSlots.Key).registerSettings(SettingsSection(
            id = "profiles", order = 60, icon = KcodeIconAsset.Settings,
            title = { profileText(Res.string.profile_title) },
            description = { profileText(Res.string.profile_description) },
            renderer = UiRenderer { request -> ProfileSettings(session, client, request.onReturn) },
        )))
    }
}
