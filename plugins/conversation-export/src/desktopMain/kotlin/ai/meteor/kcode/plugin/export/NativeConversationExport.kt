package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.Context
import org.cordis.EffectScope

internal actual fun nativeConversationImageSaverFactory(ctx: Context, effect: EffectScope): ConversationImageSaverFactory {
    val inputs = PluginHostInputs.current(ctx, effect) as? DesktopPluginHostInputs
        ?: return unsupportedConversationImageSaverFactory()
    return desktopConversationImageSaverFactory(inputs)
}
