package ai.meteor.kcode.ui.component

import androidx.compose.ui.window.DialogProperties

internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = false,
    usePlatformDefaultWidth = false,
)
