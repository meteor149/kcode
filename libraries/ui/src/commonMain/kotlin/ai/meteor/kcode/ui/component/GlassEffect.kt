package ai.meteor.kcode.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import ai.meteor.kcode.ui.design.KcodeGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

class KcodeHazeState internal constructor(internal val delegate: HazeState)

@Composable
fun rememberKcodeHazeState(): KcodeHazeState {
    val state = rememberHazeState()
    return remember(state) { KcodeHazeState(state) }
}

fun Modifier.kcodeHazeSource(state: KcodeHazeState): Modifier = hazeSource(state.delegate)

@Composable
fun Modifier.kcodeGlassEffect(
    hazeState: KcodeHazeState,
    shape: Shape,
): Modifier {
    val background = MaterialTheme.colorScheme.surface
    val glass = KcodeGlass
    return clip(shape).hazeEffect(hazeState.delegate) {
        backgroundColor = background
        blurRadius = glass.blurRadius
        tints = listOf(HazeTint(background.copy(alpha = glass.tintOpacity)))
        noiseFactor = glass.noiseFactor
        blurredEdgeTreatment = BlurredEdgeTreatment(shape)
    }
}
