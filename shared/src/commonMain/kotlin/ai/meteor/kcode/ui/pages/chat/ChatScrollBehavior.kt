package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.ui.state.ConversationState

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.Job

/** Keeps presentation-only scrolling out of the model request coroutine. */
internal class StreamScrollFollower {
    var job: Job? = null
    var followLatest: Boolean = true
    private var userScrollInProgress: Boolean = false

    fun stopFollowing() {
        followLatest = false
        job?.cancel()
    }

    fun onScrollStateChanged(isScrollInProgress: Boolean, isAtBottom: Boolean) {
        if (isScrollInProgress && job?.isActive != true) {
            userScrollInProgress = true
            stopFollowing()
        } else if (!isScrollInProgress && userScrollInProgress) {
            userScrollInProgress = false
            if (isAtBottom) followLatest = true
        }
    }

    fun shouldScrollProgrammatically(): Boolean = followLatest
}

internal fun LazyListState.isAtConversationBottom(contentLastIndex: Int): Boolean {
    if (contentLastIndex < 0) return true
    val layout = layoutInfo
    val lastItem = layout.visibleItemsInfo.firstOrNull { it.index == contentLastIndex } ?: return false
    return lastItem.offset + lastItem.size + layout.afterContentPadding <= layout.viewportEndOffset + 1
}

internal suspend fun LazyListState.animateToConversationBottom(targetIndex: Int) =
    animateToBottom(targetIndex)

internal suspend fun LazyListState.scrollToConversationBottom(targetIndex: Int) {
    if (targetIndex < 0) return
    var layout = layoutInfo
    var target = layout.visibleItemsInfo.lastOrNull { it.index == targetIndex }
    if (target == null) {
        scrollToItem(targetIndex)
        layout = layoutInfo
        target = layout.visibleItemsInfo.lastOrNull { it.index == targetIndex } ?: return
    }
    val remainingDistance = (
        target.offset + target.size + layout.afterContentPadding - layout.viewportEndOffset
    ).coerceAtLeast(0)
    if (remainingDistance > 0) scrollBy(remainingDistance.toFloat() + 1f)
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

internal fun ConversationState.latestContentIndex(): Int =
    messages.lastIndex + if (isGenerating && isAwaitingFirstToken) 1 else 0
