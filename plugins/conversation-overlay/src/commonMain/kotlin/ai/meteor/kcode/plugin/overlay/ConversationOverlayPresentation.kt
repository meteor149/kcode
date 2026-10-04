package ai.meteor.kcode.plugin.overlay

import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.localization.LocalizationContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.StateFlow

/** System windows receive the same committed presentation contributions as application pages. */
@Composable
internal fun ConversationOverlayPresentation(
    committedSlots: StateFlow<ApplicationUiSlots>,
    content: @Composable () -> Unit,
) {
    val slots by committedSlots.collectAsState()
    CompositionLocalProvider(LocalApplicationUiSlots provides slots) {
        LocalizationContext(slots.localization, "") {
            key(slots.theme) { slots.theme?.Render(content) }
        }
    }
}
