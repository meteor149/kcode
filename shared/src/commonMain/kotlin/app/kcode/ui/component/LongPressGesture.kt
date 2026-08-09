package app.kcode.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/** Delivers a long press only after release so newly composed selection fields cannot consume the tail of the gesture. */
internal fun Modifier.onLongPressAfterRelease(key: Any?, onLongPress: (Offset) -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val endedBeforeThreshold = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var ended = false
                while (!ended) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    ended = change == null || !change.pressed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
            if (endedBeforeThreshold != null) return@awaitEachGesture

            var released = false
            while (!released) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                released = change == null || !change.pressed
            }
            onLongPress(down.position)
        }
    }
