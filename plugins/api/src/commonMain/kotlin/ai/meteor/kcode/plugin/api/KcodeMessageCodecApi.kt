package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.model.ChatMessageCodec
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeMessageCodec(ctx: Context, val codec: ChatMessageCodec) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeMessageCodec>("messageCodec") }
}
