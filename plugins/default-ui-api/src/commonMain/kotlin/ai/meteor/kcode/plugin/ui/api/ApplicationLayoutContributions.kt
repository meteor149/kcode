package ai.meteor.kcode.plugin.ui.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

@Composable
fun RenderApplicationLayout(request: ApplicationLayoutRequest, renderer: UiRenderer<ApplicationLayoutRequest>?) {
    if (renderer != null) key(renderer) { renderer.Render(request) }
}

@Composable
fun RenderApplicationSidebar(request: SidebarPageRequest, renderer: UiRenderer<SidebarPageRequest>?) {
    if (renderer != null) key(renderer) { renderer.Render(request) }
}
