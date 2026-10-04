package ai.meteor.kcode.plugin.markdown

import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.KcodeMarkdown
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object MarkdownProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "provider.markdown.default"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val content = DefaultMarkdownContent()
        effect.collect(Disposable { content.close() })
        KcodeMarkdown(ctx, content)
    }
}

object MarkdownUiContributionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "consumer.markdown.ui"
    override val inject = dependencies(KcodeMarkdown.Key, KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.Markdown, ctx.require(KcodeMarkdown.Key).content))
    }
}
