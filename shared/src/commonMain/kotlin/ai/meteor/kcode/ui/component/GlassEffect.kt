package ai.meteor.kcode.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

expect class KcodeHazeState

@Composable
internal expect fun rememberKcodeHazeState(): KcodeHazeState

internal expect fun Modifier.kcodeHazeSource(state: KcodeHazeState): Modifier

@Composable
internal expect fun Modifier.kcodeGlassEffect(
    hazeState: KcodeHazeState,
    shape: Shape,
): Modifier
