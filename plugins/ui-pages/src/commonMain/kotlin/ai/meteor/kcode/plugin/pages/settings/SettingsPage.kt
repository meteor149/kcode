package ai.meteor.kcode.plugin.pages.settings

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.ui.component.BottomSheetOverlay
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager

@Composable
fun SettingsPageOverlay(request: SettingsPageRequest) {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = request.sections.mapNotNull { if (it.isVisible(request)) it else null }
    val selected = sections.firstOrNull { it.id == route }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(route, selected) {
        focusManager.clearFocus(force = true)
        if (route != null && selected == null) route = null
    }
    BottomSheetOverlay(
        onDismissRequest = request.onDismiss,
        onBackRequest = if (route == null) null else ({ route = null }),
    ) { dismissSheet ->
        Column(Modifier.fillMaxSize()) {
            SettingsWindowHeader(
                title = selected?.title?.invoke() ?: text(UiText.Settings),
                isRoot = selected == null,
                onNavigation = { if (selected == null) dismissSheet() else route = null },
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (selected == null) {
                    SettingsHome(request, sections) { route = it }
                } else {
                    key(selected) {
                        selected.renderer.Render(SettingsSectionRequest(request) { route = null })
                    }
                }
            }
        }
    }
}
