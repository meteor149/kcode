package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.plugin.ui.api.MarkdownContent
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeMarkdown(ctx: Context, val content: MarkdownContent) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeMarkdown>("markdown") }
}
