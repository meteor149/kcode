@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.kcode.export

import app.kcode.ui.component.MarkdownAccent
import app.kcode.ui.component.MarkdownBlock
import app.kcode.ui.component.MarkdownPanel
import app.kcode.ui.component.MarkdownRule
import app.kcode.ui.component.MarkdownSoftInk
import app.kcode.ui.component.inlineMarkdown
import app.kcode.ui.component.parseMarkdownBlocks
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

data class ConversationExportMessage(
    val isUser: Boolean,
    val content: String,
    val isError: Boolean,
)

data class RenderedConversationImage(
    val image: ImageBitmap,
    val truncated: Boolean,
)

private enum class BlockSurface { None, Panel, Quote }

private sealed interface ExportBlockLayout {
    val y: Float
    val height: Float

    data class Text(
        val layout: TextLayoutResult,
        override val y: Float,
        val x: Float = 0f,
        val surface: BlockSurface = BlockSurface.None,
        val surfaceWidth: Float = 0f,
        val padding: Float = 0f,
    ) : ExportBlockLayout {
        override val height: Float = layout.size.height + padding * 2
    }

    data class Rule(override val y: Float, val width: Float) : ExportBlockLayout {
        override val height: Float = 2f
    }
}

private data class RenderMessage(
    val message: ConversationExportMessage,
    val blocks: List<ExportBlockLayout>,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

private data class MeasuredMarkdown(
    val blocks: List<ExportBlockLayout>,
    val width: Float,
    val height: Float,
    val overflow: Boolean,
)

suspend fun renderConversationImage(
    textMeasurer: TextMeasurer,
    graphicsLayer: GraphicsLayer,
    layoutDirection: LayoutDirection,
    title: String,
    messages: List<ConversationExportMessage>,
    truncatedLabel: String,
): RenderedConversationImage {
    val density = Density(2f)
    val width = 1080
    val maxHeight = 7680
    val side = 72
    val top = 72
    val bodyWidth = width - side * 2
    val userContentWidth = 720
    val bubblePaddingX = 34
    val bubblePaddingY = 24
    val messageGap = 52
    val footerReserve = 116
    val ink = Color(0xFF202622)
    val error = Color(0xFF9B403C)
    val userPanel = Color(0xFFF1F1EF)

    val titleLayout = textMeasurer.measure(
        text = title,
        style = TextStyle(color = ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        density = density,
        constraints = Constraints(maxWidth = bodyWidth),
    )
    var y = top + titleLayout.size.height + 54
    var truncated = false
    val items = mutableListOf<RenderMessage>()

    for (message in messages) {
        val remaining = maxHeight - y - footerReserve
        if (remaining < 100) {
            truncated = true
            break
        }
        val contentWidth = if (message.isUser) userContentWidth else bodyWidth
        val measured = measureMarkdown(
            textMeasurer = textMeasurer,
            markdown = message.content,
            color = if (message.isError) error else ink,
            maxWidth = contentWidth,
            maxHeight = remaining - if (message.isUser) bubblePaddingY * 2 else 0,
            density = density,
        )
        val itemWidth = if (message.isUser) {
            (measured.width + bubblePaddingX * 2).coerceAtMost((contentWidth + bubblePaddingX * 2).toFloat())
        } else {
            bodyWidth.toFloat()
        }
        val itemHeight = measured.height + if (message.isUser) bubblePaddingY * 2 else 0
        val itemX = if (message.isUser) width - side - itemWidth else side.toFloat()
        items += RenderMessage(message, measured.blocks, itemX, y.toFloat(), itemWidth, itemHeight)
        y += itemHeight.toInt() + messageGap
        if (measured.overflow) {
            truncated = true
            break
        }
    }

    val footerLayout = if (truncated) textMeasurer.measure(
        text = truncatedLabel,
        style = TextStyle(color = MarkdownSoftInk, fontSize = 13.sp),
        density = density,
        constraints = Constraints(maxWidth = bodyWidth),
    ) else null
    val footerHeight = footerLayout?.size?.height?.plus(42) ?: 0
    val outputHeight = (y + footerHeight + 36).coerceIn(420, maxHeight)

    graphicsLayer.record(density, layoutDirection, IntSize(width, outputHeight)) {
        drawRect(Color.White)
        drawText(titleLayout, topLeft = Offset(side.toFloat(), top.toFloat()))
        items.forEach { item ->
            if (item.message.isUser) {
                drawRoundRect(
                    color = userPanel,
                    topLeft = Offset(item.x, item.y),
                    size = Size(item.width, item.height),
                    cornerRadius = CornerRadius(34f),
                )
            }
            val contentX = item.x + if (item.message.isUser) bubblePaddingX else 0
            val contentY = item.y + if (item.message.isUser) bubblePaddingY else 0
            item.blocks.forEach { block ->
                when (block) {
                    is ExportBlockLayout.Text -> {
                        val blockX = contentX + block.x
                        val blockY = contentY + block.y
                        when (block.surface) {
                            BlockSurface.Panel -> drawRoundRect(
                                color = MarkdownPanel,
                                topLeft = Offset(blockX, blockY),
                                size = Size(block.surfaceWidth, block.height),
                                cornerRadius = CornerRadius(18f),
                            )
                            BlockSurface.Quote -> drawRoundRect(
                                color = MarkdownAccent,
                                topLeft = Offset(blockX, blockY),
                                size = Size(6f, block.height),
                                cornerRadius = CornerRadius(3f),
                            )
                            BlockSurface.None -> Unit
                        }
                        drawText(
                            block.layout,
                            topLeft = Offset(blockX + block.padding, blockY + block.padding),
                        )
                    }
                    is ExportBlockLayout.Rule -> drawRect(
                        color = MarkdownRule,
                        topLeft = Offset(contentX, contentY + block.y),
                        size = Size(block.width, block.height),
                    )
                }
            }
        }
        footerLayout?.let {
            drawText(it, topLeft = Offset(side.toFloat(), (outputHeight - it.size.height - 36).toFloat()))
        }
    }
    return RenderedConversationImage(graphicsLayer.toImageBitmap(), truncated)
}

private fun measureMarkdown(
    textMeasurer: TextMeasurer,
    markdown: String,
    color: Color,
    maxWidth: Int,
    maxHeight: Int,
    density: Density,
): MeasuredMarkdown {
    val blockGap = 18f
    val panelPadding = 22f
    val quoteIndent = 24f
    var y = 0f
    var measuredWidth = 0f
    var overflow = false
    val layouts = mutableListOf<ExportBlockLayout>()

    fun addText(
        text: AnnotatedString,
        style: TextStyle,
        width: Int = maxWidth,
        x: Float = 0f,
        surface: BlockSurface = BlockSurface.None,
        padding: Float = 0f,
    ) {
        if (y >= maxHeight) {
            overflow = true
            return
        }
        val availableWidth = (width - padding.toInt() * 2).coerceAtLeast(1)
        val availableHeight = (maxHeight - y.toInt() - padding.toInt() * 2).coerceAtLeast(1)
        val layout = textMeasurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 120,
            density = density,
            constraints = Constraints(maxWidth = availableWidth, maxHeight = availableHeight),
        )
        val surfaceWidth = if (surface == BlockSurface.None) 0f else width.toFloat()
        val block = ExportBlockLayout.Text(layout, y, x, surface, surfaceWidth, padding)
        layouts += block
        measuredWidth = maxOf(
            measuredWidth,
            if (surface == BlockSurface.Panel) x + width else x + layout.size.width + padding * 2,
        )
        y += block.height + blockGap
        overflow = overflow || layout.hasVisualOverflow || y - blockGap > maxHeight
    }

    val body = TextStyle(color = color, fontSize = 17.sp, lineHeight = 27.sp)
    parseMarkdownBlocks(markdown).forEach { block ->
        if (overflow) return@forEach
        when (block) {
            is MarkdownBlock.Paragraph -> addText(inlineMarkdown(block.text, color), body)
            is MarkdownBlock.Heading -> addText(
                inlineMarkdown(block.text, color),
                body.copy(
                    fontSize = when (block.level) { 1 -> 25.sp; 2 -> 22.sp; else -> 19.sp },
                    lineHeight = when (block.level) { 1 -> 33.sp; 2 -> 30.sp; else -> 27.sp },
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            is MarkdownBlock.Quote -> addText(
                inlineMarkdown(block.text, MarkdownSoftInk),
                body.copy(color = MarkdownSoftInk, fontStyle = FontStyle.Italic),
                width = maxWidth - quoteIndent.toInt(),
                x = quoteIndent,
                surface = BlockSurface.Quote,
                padding = 12f,
            )
            is MarkdownBlock.Code -> {
                val code = buildAnnotatedString {
                    if (block.language.isNotBlank()) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = MarkdownSoftInk, fontWeight = FontWeight.Medium))
                        append(block.language)
                        pop()
                        append("\n")
                    }
                    append(block.code)
                }
                addText(
                    code,
                    body.copy(fontSize = 15.sp, lineHeight = 23.sp, fontFamily = FontFamily.Monospace),
                    surface = BlockSurface.Panel,
                    padding = panelPadding,
                )
            }
            is MarkdownBlock.ListBlock -> {
                val list = buildAnnotatedString {
                    block.items.forEachIndexed { index, item ->
                        if (index > 0) append("\n")
                        append(if (block.ordered) "${block.start + index}.  " else "•  ")
                        append(inlineMarkdown(item, color))
                    }
                }
                addText(list, body)
            }
            is MarkdownBlock.Table -> {
                val table = buildAnnotatedString {
                    block.rows.forEachIndexed { rowIndex, cells ->
                        if (rowIndex > 0) append("\n")
                        if (rowIndex == 0) pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
                        append(cells.joinToString("   |   "))
                        if (rowIndex == 0) pop()
                    }
                }
                addText(table, body.copy(fontSize = 15.sp, lineHeight = 24.sp), surface = BlockSurface.Panel, padding = panelPadding)
            }
            MarkdownBlock.Rule -> {
                if (y + 2f > maxHeight) overflow = true
                else {
                    layouts += ExportBlockLayout.Rule(y, maxWidth.toFloat())
                    measuredWidth = maxOf(measuredWidth, maxWidth.toFloat())
                    y += 2f + blockGap
                }
            }
        }
    }

    return MeasuredMarkdown(
        blocks = layouts,
        width = measuredWidth.coerceAtMost(maxWidth.toFloat()),
        height = (y - blockGap).coerceAtLeast(0f),
        overflow = overflow,
    )
}
