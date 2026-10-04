package ai.meteor.kcode.plugin.messagecodec

import ai.meteor.kcode.plugin.api.KcodeMessageCodec
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

object MessageCodecProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "provider.message-codec.envelope"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val codec = EnvelopeChatMessageCodec()
        effect.collect(Disposable { codec.close() })
        KcodeMessageCodec(ctx, codec)
    }
}
