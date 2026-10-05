package ai.meteor.kcode.plugin.notifications

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.localization.configuredLanguage
import ai.meteor.kcode.localization.UiText
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Explicit channel configuration is independent of the default dictionary. */
class AndroidNativeNotificationsPlugin : Plugin<String> {
    override val name = "android-native-notifications"
    override val config = ConfigValidator<String> { require(it.isNotBlank()) { "Notification channel name is blank" }; it }
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android notifications require native host inputs"
        }
        ScheduledTaskNotificationsProviderPlugin.apply(ctx, androidScheduledTaskNotificationsFactory(inputs, config), effect)
    }
}

class LocalizedAndroidNativeNotificationsPlugin : Plugin<Unit> {
    override val name = "android-localized-native-notifications"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeSettings.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val catalog = ctx.require(KcodeLocalization.Key).catalog
        val language = catalog.configuredLanguage(ctx.require(KcodeSettings.Key).store.load())
        AndroidNativeNotificationsPlugin().apply(ctx, catalog.translate(language, UiText.ScheduledTaskNotificationChannel), effect)
    }
}
