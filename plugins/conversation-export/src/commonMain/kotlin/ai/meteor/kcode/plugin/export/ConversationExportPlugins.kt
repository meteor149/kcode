package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.export.ConversationExportMessage
import ai.meteor.kcode.export.ConversationExportRequest
import ai.meteor.kcode.export.ConversationExportResult
import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ConversationImageRenderContext
import ai.meteor.kcode.export.ConversationImageRenderRequest
import ai.meteor.kcode.export.ConversationImageRenderer
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ExportAction
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.KcodeConversationExport
import ai.meteor.kcode.plugin.api.KcodeConversationImageRendering
import ai.meteor.kcode.plugin.api.KcodeConversationImageSaving
import ai.meteor.kcode.plugin.ui.api.KcodeMarkdown
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import org.cordis.Disposable
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object ConversationImageRenderingPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "conversation-image-rendering"
    override val inject = dependencies(KcodeMarkdown.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val markdown = ctx.require(KcodeMarkdown.Key).content
        val owner = PluginOperationOwner(name)
        effect.collect(Disposable { owner.close() })
        KcodeConversationImageRendering(ctx, ConversationImageRenderer { request, context ->
            owner.run {
                renderConversationImage(
                    formatter = markdown,
                    textMeasurer = context.textMeasurer,
                    graphicsLayer = context.graphicsLayer,
                    layoutDirection = context.layoutDirection,
                    title = request.title,
                    messages = request.messages,
                    truncatedLabel = request.truncatedLabel,
                )
            }
        })
    }
}

object ConversationExportPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "conversation-export"
    override val inject = dependencies(KcodeConversationImageRendering.Key, KcodeConversationImageSaving.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val exporter = ImageConversationExporter(
            ctx.require(KcodeConversationImageRendering.Key).renderer,
            ctx.require(KcodeConversationImageSaving.Key).saver,
        )
        KcodeConversationExport(ctx, exporter)
        effect.collect(Disposable { exporter.close() })
    }
}

internal fun redactExportSecrets(content: String, activeSecret: String): String {
    val redacted = if (activeSecret.isBlank()) content else content.replace(activeSecret, "••••")
    return Regex("sk[-_][A-Za-z0-9_-]{6,}", RegexOption.IGNORE_CASE).replace(redacted, "••••")
}

internal fun messagesForExport(messages: List<ChatMessage>, selectedIds: Set<Long>?): List<ChatMessage> =
    if (selectedIds == null) messages.toList() else messages.filter { it.id in selectedIds }

internal class ImageConversationExporter(
    private val renderer: ConversationImageRenderer,
    private val saver: ConversationImageSaver,
) : ConversationExporter {
    private val owner = PluginOperationOwner("conversation-export")

    override suspend fun export(
        request: ConversationExportRequest,
        action: ExportAction,
        context: ConversationImageRenderContext,
    ): ConversationExportResult = owner.run {
        val messages = messagesForExport(request.messages, request.selectedIds)
        require(messages.isNotEmpty()) { "No messages selected for export" }
        val rendered = renderer.render(ConversationImageRenderRequest(
            title = redactExportSecrets(request.title, request.activeSecret),
            messages = messages.map {
                ConversationExportMessage(
                    isUser = it.role == MessageRole.User,
                    content = redactExportSecrets(it.content, request.activeSecret),
                    isError = it.isError,
                )
            },
            truncatedLabel = request.truncatedLabel,
        ), context)
        val fileName = "kcode-${request.conversationId}.png"
        ConversationExportResult(rendered.truncated, when (action) {
            ExportAction.Save -> saver.save(rendered.image, fileName)
            ExportAction.Share -> saver.share(rendered.image, fileName)
        })
    }

    suspend fun close() = owner.close()
}
