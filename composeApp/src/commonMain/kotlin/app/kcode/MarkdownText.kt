package app.kcode

import app.kcode.ui.KcodeRadius
import app.kcode.ui.KcodeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

internal val MarkdownSoftInk = Color(0xFF727570)
internal val MarkdownPanel = Color(0xFFF3F4F1)
internal val MarkdownRule = Color(0xFFE4E5E2)
internal val MarkdownAccent = Color(0xFF3E7653)

/** A lightweight, synchronous renderer that remains stable while tokens are still arriving. */
@Composable
internal fun MarkdownText(
    markdown: String,
    compact: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onLongPressText: ((text: String, offset: Int) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val bodyStyle = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium

    Column(modifier, verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> LongPressMarkdownText(
                    content = inlineMarkdown(block.text, color),
                    color = color,
                    style = bodyStyle,
                    onLongPressText = onLongPressText,
                )

                is MarkdownBlock.Heading -> LongPressMarkdownText(
                    content = inlineMarkdown(block.text, color),
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                    onLongPressText = onLongPressText,
                )

                is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).background(MarkdownAccent, RoundedCornerShape(2.dp))) {
                        Spacer(Modifier.padding(vertical = 18.dp))
                    }
                    LongPressMarkdownText(
                        content = inlineMarkdown(block.text, MarkdownSoftInk),
                        modifier = Modifier.padding(start = KcodeSpacing.sm),
                        color = MarkdownSoftInk,
                        style = bodyStyle,
                        fontStyle = FontStyle.Italic,
                        onLongPressText = onLongPressText,
                    )
                }

                is MarkdownBlock.Code -> Column(
                    Modifier.fillMaxWidth()
                        .background(MarkdownPanel, RoundedCornerShape(KcodeRadius.control))
                        .padding(vertical = KcodeSpacing.xs),
                ) {
                    if (block.language.isNotBlank()) {
                        Text(
                            block.language,
                            Modifier.padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.hair),
                            color = MarkdownSoftInk,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    LongPressMarkdownText(
                        content = AnnotatedString(block.code),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = KcodeSpacing.sm),
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        onLongPressText = onLongPressText,
                    )
                }

                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.hair)) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                if (block.ordered) "${block.start + index}." else "•",
                                Modifier.width(28.dp),
                                color = color,
                                style = bodyStyle,
                                fontWeight = if (block.ordered) FontWeight.Medium else FontWeight.Bold,
                            )
                            LongPressMarkdownText(
                                content = inlineMarkdown(item, color),
                                modifier = Modifier.weight(1f),
                                color = color,
                                style = bodyStyle,
                                onLongPressText = onLongPressText,
                            )
                        }
                    }
                }

                is MarkdownBlock.Table -> Column(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .background(MarkdownPanel, RoundedCornerShape(KcodeRadius.control))
                        .padding(vertical = KcodeSpacing.hair),
                ) {
                    block.rows.forEachIndexed { rowIndex, cells ->
                        Row {
                            cells.forEach { cell ->
                                LongPressMarkdownText(
                                    content = inlineMarkdown(cell, color),
                                    modifier = Modifier.widthIn(min = 110.dp, max = 190.dp)
                                        .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
                                    color = color,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    onLongPressText = onLongPressText,
                                )
                            }
                        }
                        if (rowIndex == 0) HorizontalDivider(color = MarkdownRule)
                    }
                }

                MarkdownBlock.Rule -> HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MarkdownRule,
                )
            }
        }
    }
}

@Composable
private fun LongPressMarkdownText(
    content: AnnotatedString,
    color: Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    fontFamily: FontFamily? = null,
    onLongPressText: ((String, Int) -> Unit)?,
) {
    var layout by remember(content) { androidx.compose.runtime.mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = content,
        modifier = if (onLongPressText == null) modifier else modifier.onLongPressAfterRelease(content.text) { position ->
                val offset = layout?.getOffsetForPosition(position) ?: 0
                onLongPressText(content.text, offset.coerceIn(0, content.text.length))
        },
        color = color,
        style = style,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        onTextLayout = { layout = it },
    )
}

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<String>) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val headingPattern = Regex("^(#{1,6})\\s+(.*)$")
private val unorderedPattern = Regex("^\\s*[-+*]\\s+(.*)$")
private val orderedPattern = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val tableDividerPattern = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
private val rulePattern = Regex("^\\s*((-{3,})|(\\*{3,})|(_{3,}))\\s*$")

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.isEmpty()) return emptyList()
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val result = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }

        if (line.trimStart().startsWith("```")) {
            val language = line.trim().removePrefix("```").trim()
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                code += lines[index++]
            }
            if (index < lines.size) index++
            result += MarkdownBlock.Code(language, code.joinToString("\n"))
            continue
        }

        headingPattern.matchEntire(line)?.let { match ->
            result += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2])
            index++
            continue
        }

        if (rulePattern.matches(line)) {
            result += MarkdownBlock.Rule
            index++
            continue
        }

        if (line.trimStart().startsWith(">")) {
            val quote = mutableListOf<String>()
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                quote += lines[index++].trimStart().removePrefix(">").trimStart()
            }
            result += MarkdownBlock.Quote(quote.joinToString("\n"))
            continue
        }

        val unordered = unorderedPattern.matchEntire(line)
        val ordered = orderedPattern.matchEntire(line)
        if (unordered != null || ordered != null) {
            val orderedList = ordered != null
            val start = ordered?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val match = if (orderedList) orderedPattern.matchEntire(lines[index]) else unorderedPattern.matchEntire(lines[index])
                if (match == null) break
                items += match.groupValues.last()
                index++
            }
            result += MarkdownBlock.ListBlock(orderedList, start, items)
            continue
        }

        if (line.contains('|') && index + 1 < lines.size && tableDividerPattern.matches(lines[index + 1])) {
            val rows = mutableListOf(splitTableRow(line))
            index += 2
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                rows += splitTableRow(lines[index++])
            }
            result += MarkdownBlock.Table(rows)
            continue
        }

        val paragraph = mutableListOf(line.trim())
        index++
        while (index < lines.size && lines[index].isNotBlank() && !startsMarkdownBlock(lines, index)) {
            paragraph += lines[index++].trim()
        }
        result += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    }
    return result
}

internal fun markdownToPlainText(markdown: String): String = parseMarkdownBlocks(markdown).joinToString("\n\n") { block ->
    when (block) {
        is MarkdownBlock.Paragraph -> inlineMarkdown(block.text, Color.Unspecified).text
        is MarkdownBlock.Heading -> inlineMarkdown(block.text, Color.Unspecified).text
        is MarkdownBlock.Quote -> inlineMarkdown(block.text, Color.Unspecified).text
        is MarkdownBlock.Code -> block.code
        is MarkdownBlock.ListBlock -> block.items.mapIndexed { index, item ->
            val marker = if (block.ordered) "${block.start + index}. " else "• "
            marker + inlineMarkdown(item, Color.Unspecified).text
        }.joinToString("\n")
        is MarkdownBlock.Table -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") }
        MarkdownBlock.Rule -> "────────"
    }
}

private fun startsMarkdownBlock(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    return line.trimStart().startsWith("```") ||
        headingPattern.matches(line) ||
        rulePattern.matches(line) ||
        line.trimStart().startsWith(">") ||
        unorderedPattern.matches(line) ||
        orderedPattern.matches(line) ||
        (line.contains('|') && index + 1 < lines.size && tableDividerPattern.matches(lines[index + 1]))
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)

internal fun inlineMarkdown(source: String, color: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (cursor < source.length) {
        val token = inlineTokenAt(source, cursor)
        if (token == null) {
            append(source[cursor++])
            continue
        }
        if (token.start > cursor) append(source.substring(cursor, token.start))
        pushStyle(token.style(color))
        append(token.label)
        pop()
        cursor = token.end
    }
}

private data class InlineToken(
    val start: Int,
    val end: Int,
    val label: String,
    val kind: InlineKind,
) {
    fun style(color: Color): SpanStyle = when (kind) {
        InlineKind.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
        InlineKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
        InlineKind.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        InlineKind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = MarkdownPanel)
        InlineKind.Link -> SpanStyle(color = MarkdownAccent, textDecoration = TextDecoration.Underline)
    }
}

private enum class InlineKind { Bold, Italic, Strike, Code, Link }

private fun inlineTokenAt(source: String, from: Int): InlineToken? {
    fun paired(marker: String, kind: InlineKind): InlineToken? {
        if (!source.startsWith(marker, from)) return null
        val close = source.indexOf(marker, from + marker.length)
        if (close <= from + marker.length) return null
        return InlineToken(from, close + marker.length, source.substring(from + marker.length, close), kind)
    }

    paired("**", InlineKind.Bold)?.let { return it }
    paired("__", InlineKind.Bold)?.let { return it }
    paired("~~", InlineKind.Strike)?.let { return it }
    paired("`", InlineKind.Code)?.let { return it }

    if (source[from] == '[') {
        val labelEnd = source.indexOf(']', from + 1)
        if (labelEnd > from + 1 && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
            val urlEnd = source.indexOf(')', labelEnd + 2)
            if (urlEnd > labelEnd + 2) {
                return InlineToken(from, urlEnd + 1, source.substring(from + 1, labelEnd), InlineKind.Link)
            }
        }
    }

    paired("*", InlineKind.Italic)?.let { return it }
    paired("_", InlineKind.Italic)?.let { return it }
    return null
}
