package app.kcode.ui.pages.chat

import app.kcode.ui.state.ConversationState

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps presentation-only scrolling out of the model request coroutine. */
internal class StreamScrollFollower {
    var job: Job? = null
    var followLatest: Boolean = true
}

internal fun LazyListState.isAtConversationBottom(reverseLayout: Boolean): Boolean =
    if (reverseLayout) {
        firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 1
    } else {
        !canScrollForward
    }

internal suspend fun LazyListState.animateToConversationBottom(reverseLayout: Boolean, targetIndex: Int) {
    if (reverseLayout) {
        animateScrollToItem(0)
    } else {
        animateToBottom(targetIndex)
    }
}

internal suspend fun LazyListState.scrollToConversationBottom(reverseLayout: Boolean, targetIndex: Int) {
    if (targetIndex < 0) return
    if (reverseLayout) {
        scrollToItem(0)
    } else {
        scrollToItem(targetIndex)
        val layout = layoutInfo
        val target = layout.visibleItemsInfo.lastOrNull { it.index == targetIndex } ?: return
        val remainingDistance = (
            target.offset + target.size + layout.afterContentPadding - layout.viewportEndOffset
        ).coerceAtLeast(0)
        if (remainingDistance > 0) scrollBy(remainingDistance.toFloat() + 1f)
    }
}

private suspend fun LazyListState.animateToBottom(targetIndex: Int) {
    if (targetIndex < 0) return
    animateScrollToItem(targetIndex)

    val layout = layoutInfo
    val target = layout.visibleItemsInfo.lastOrNull { it.index == targetIndex } ?: return
    val remainingDistance = (
        target.offset + target.size + layout.afterContentPadding - layout.viewportEndOffset
    ).coerceAtLeast(0)
    if (remainingDistance > 0) {
        // One extra pixel avoids leaving canScrollForward=true because of rounding.
        animateScrollBy(remainingDistance.toFloat() + 1f)
    }
}

/**
 * Coordinates keyboard appearance with the conversation list. When the list is already at the
 * bottom, IME resize naturally moves the visible content. Otherwise we wait for the IME to settle
 * before scrolling, avoiding per-frame scroll work and the visual lag it causes.
 */
@Composable
internal fun rememberPrepareForKeyboard(
    compact: Boolean,
    listState: LazyListState,
    conversation: ConversationState?,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val currentConversation by rememberUpdatedState(conversation)
    var settleJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { settleJob?.cancel() }
    }

    return remember(compact, listState, density, imeInsets) {
        {
            val startedAtBottom = listState.isAtConversationBottom(reverseLayout = compact)
            settleJob?.cancel()
            settleJob = if (compact && !startedAtBottom) {
                scope.launch {
                    var previousBottom = -1
                    var stableFrames = 0
                    var keyboardObserved = false

                    repeat(60) {
                        withFrameNanos { }
                        val currentBottom = imeInsets.getBottom(density)
                        if (currentBottom > 0) keyboardObserved = true
                        stableFrames = if (keyboardObserved && currentBottom == previousBottom) {
                            stableFrames + 1
                        } else {
                            0
                        }
                        previousBottom = currentBottom
                        if (stableFrames >= 8) {
                            currentConversation?.scrollToLatest(listState)
                            return@launch
                        }
                    }
                    currentConversation?.scrollToLatest(listState)
                }
            } else {
                null
            }
            Unit
        }
    }
}

private suspend fun ConversationState.scrollToLatest(listState: LazyListState) {
    if (messages.isEmpty()) return
    val lastIndex = messages.lastIndex + if (isGenerating) 1 else 0
    listState.animateToConversationBottom(reverseLayout = true, targetIndex = lastIndex)
}
