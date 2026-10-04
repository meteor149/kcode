package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.chat.ChatGenerationRunner
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** Generation admission and activity belong to a replaceable product provider. */
class KcodeGeneration(ctx: Context, val runner: ChatGenerationRunner) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeGeneration>("generation") }
}
