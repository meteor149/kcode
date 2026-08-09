package ai.meteor.kcode.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

actual typealias KcodeHazeState = HazeState

@Composable
internal actual fun rememberKcodeHazeState(): KcodeHazeState = rememberHazeState()

internal actual fun Modifier.kcodeHazeSource(state: KcodeHazeState): Modifier = hazeSource(state)

@Composable
internal actual fun Modifier.kcodeGlassEffect(
    hazeState: KcodeHazeState,
    shape: Shape,
): Modifier {
    val background = MaterialTheme.colorScheme.surface
    return clip(shape).hazeEffect(hazeState) {
        backgroundColor = background
        blurRadius = 16.dp
        tints = listOf(HazeTint(background.copy(alpha = 0.72f)))
        noiseFactor = 0.025f
        blurredEdgeTreatment = BlurredEdgeTreatment(shape)
    }
}
