package ai.meteor.kcode.ui.component

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape

actual class KcodeHazeState

@Composable
internal actual fun rememberKcodeHazeState(): KcodeHazeState = remember { KcodeHazeState() }

internal actual fun Modifier.kcodeHazeSource(state: KcodeHazeState): Modifier = this

@Composable
internal actual fun Modifier.kcodeGlassEffect(
    hazeState: KcodeHazeState,
    shape: Shape,
): Modifier {
    val background = MaterialTheme.colorScheme.surface
    return clip(shape).background(background.copy(alpha = 0.92f))
}
