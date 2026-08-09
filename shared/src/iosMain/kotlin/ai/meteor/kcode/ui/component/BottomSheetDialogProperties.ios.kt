package ai.meteor.kcode.ui.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = false,
    usePlatformDefaultWidth = false,
    useSoftwareKeyboardInset = false,
)
