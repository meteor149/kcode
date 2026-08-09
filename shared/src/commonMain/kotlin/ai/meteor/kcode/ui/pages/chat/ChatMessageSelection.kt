@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.model.ChatMessage

import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.onLongPressAfterRelease
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Owns the transient multi-message selection state for one conversation. */
@Stable
internal class MessageSelectionState {
    private val selectedIds = mutableStateListOf<Long>()

    var active by mutableStateOf(false)
        private set

    val ids: Set<Long>
        get() = selectedIds.toSet()

    val count: Int
        get() = selectedIds.size

    val isEmpty: Boolean
        get() = selectedIds.isEmpty()

    fun begin(message: ChatMessage) {
        active = true
        if (message.id !in selectedIds) selectedIds += message.id
    }

    fun toggle(message: ChatMessage) {
        if (message.id in selectedIds) selectedIds.remove(message.id) else selectedIds += message.id
    }

    fun clear() {
        active = false
        selectedIds.clear()
    }
}

@Composable
internal fun rememberMessageSelectionState(conversationId: Long?): MessageSelectionState =
    remember(conversationId) { MessageSelectionState() }

internal fun sentenceSelectionRange(text: String, requestedOffset: Int): TextRange {
    if (text.isEmpty()) return TextRange.Zero
    fun isSentenceTerminator(index: Int): Boolean = when (text[index]) {
        '。', '！', '？', '!', '?', '；', ';', '\n' -> true
        '.' -> {
            val previous = text.getOrNull(index - 1)
            val next = text.getOrNull(index + 1)
            previous?.isLetterOrDigit() != true || next?.isLetterOrDigit() != true
        }
        else -> false
    }
    var probe = requestedOffset.coerceIn(0, text.lastIndex)
    while (probe < text.lastIndex && text[probe].isWhitespace()) probe++

    var start = probe
    while (start > 0 && !isSentenceTerminator(start - 1)) start--
    while (start < text.length && text[start].isWhitespace()) start++

    var end = probe
    while (end < text.length && !isSentenceTerminator(end)) end++
    if (end < text.length && text[end] != '\n') end++
    while (end > start && text[end - 1].isWhitespace()) end--

    return if (start < end) {
        TextRange(start, end)
    } else {
        TextRange(probe, (probe + 1).coerceAtMost(text.length))
    }
}
@Composable
internal fun MessageSelectionEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    compact: Boolean,
    isUser: Boolean,
    isError: Boolean,
) {
    val shape = RoundedCornerShape(KcodeRadius.card)
    var textLayout by remember(value.text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(focusRequester) {
        // This effect belongs to the field itself, so cancellation follows its attachment.
        // Waiting one frame avoids requesting focus before the FocusRequester node is mounted.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        Modifier.widthIn(max = if (isUser) 560.dp else 680.dp)
            .fillMaxWidth(if (isUser) .86f else 1f)
            .background(if (isUser) Panel else Color.Transparent, shape)
            .padding(horizontal = if (isUser) KcodeSpacing.md else 0.dp, vertical = KcodeSpacing.sm),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                readOnly = true,
                textStyle = (if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium).copy(
                    color = if (isError) Error else Ink,
                ),
                cursorBrush = SolidColor(LeafInk),
                onTextLayout = { textLayout = it },
            )
            textLayout?.let { layout ->
                val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
                val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
                AdjustableSelectionHandle(
                    cursor = layout.getCursorRect(start).let { Offset(it.left, it.bottom) },
                    layout = layout,
                    startHandle = true,
                    onOffsetChange = { offset ->
                        onValueChange(value.copy(selection = TextRange(offset.coerceAtMost(end), end)))
                    },
                )
                AdjustableSelectionHandle(
                    cursor = layout.getCursorRect(end).let { Offset(it.left, it.bottom) },
                    layout = layout,
                    startHandle = false,
                    onOffsetChange = { offset ->
                        onValueChange(value.copy(selection = TextRange(start, offset.coerceAtLeast(start))))
                    },
                )
            }
        }
    }
}

@Composable
private fun AdjustableSelectionHandle(
    cursor: Offset,
    layout: TextLayoutResult,
    startHandle: Boolean,
    onOffsetChange: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val touchSize = 34.dp
    val touchSizePx = with(density) { touchSize.toPx() }
    val stemInsetPx = with(density) { 2.dp.toPx() }
    val latestOffsetChange by rememberUpdatedState(onOffsetChange)
    Box(
        Modifier.offset {
            IntOffset(
                // Keep the visual and its full touch target inside the text bounds. The two
                // handles intentionally open toward the selected text, like native handles.
                x = (if (startHandle) cursor.x else cursor.x - touchSizePx).roundToInt(),
                y = (cursor.y - stemInsetPx).roundToInt(),
            )
        }.size(touchSize)
            .pointerInput(layout, startHandle) {
                var target = cursor
                detectDragGestures(
                    onDragStart = { target = cursor },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        target += dragAmount
                        val constrained = Offset(
                            x = target.x.coerceIn(0f, layout.size.width.toFloat()),
                            y = target.y.coerceIn(0f, layout.size.height.toFloat()),
                        )
                        latestOffsetChange(layout.getOffsetForPosition(constrained))
                    },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stemX = if (startHandle) 2.5.dp.toPx() else size.width - 2.5.dp.toPx()
            val bulbX = if (startHandle) 6.dp.toPx() else size.width - 6.dp.toPx()
            drawRoundRect(
                color = LeafInk,
                topLeft = Offset(stemX - 1.5.dp.toPx(), 0f),
                size = Size(3.dp.toPx(), 13.dp.toPx()),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            drawCircle(color = LeafInk, radius = 5.5.dp.toPx(), center = Offset(bulbX, 15.dp.toPx()))
            drawCircle(color = Color.White, radius = 2.7.dp.toPx(), center = Offset(bulbX, 15.dp.toPx()))
        }
    }
}

@Composable
internal fun CompactCopyAction(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .padding(4.dp)
            .clip(RoundedCornerShape(KcodeRadius.control))
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Button)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun LongPressMessageText(
    content: String,
    color: Color,
    style: TextStyle,
    onLongPressText: (String, Int) -> Unit,
) {
    var layout by remember(content) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = content,
        modifier = Modifier.onLongPressAfterRelease(content) { position ->
                onLongPressText(content, layout?.getOffsetForPosition(position) ?: 0)
        },
        color = color,
        style = style,
        onTextLayout = { layout = it },
    )
}

@Composable
internal fun SelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(21.dp)
            .clip(CircleShape)
            .background(if (selected) LeafInk else Color.White)
            .border(1.5.dp, if (selected) LeafInk else SoftInk.copy(alpha = .7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
