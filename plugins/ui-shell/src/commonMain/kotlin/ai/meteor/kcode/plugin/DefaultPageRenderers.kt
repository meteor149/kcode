package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.pages.settings.SettingsPageOverlay
import ai.meteor.kcode.plugin.pages.sidebar.Sidebar
import ai.meteor.kcode.plugin.pages.sidebar.SidebarScaffold
import ai.meteor.kcode.plugin.ui.api.ApplicationLayoutRequest
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.SidebarPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.runtime.Composable

object DefaultSettingsPageRenderer : UiRenderer<SettingsPageRequest> {
    @Composable
    override fun Render(request: SettingsPageRequest) {
        SettingsPageOverlay(request)
    }
}

object DefaultApplicationLayoutRenderer : UiRenderer<ApplicationLayoutRequest> {
    @Composable
    override fun Render(request: ApplicationLayoutRequest) = SidebarScaffold(request)
}

object DefaultSidebarRenderer : UiRenderer<SidebarPageRequest> {
    @Composable
    override fun Render(request: SidebarPageRequest) {
        Sidebar(
            conversations = request.conversations,
            activeId = request.activeId,
            destination = request.destination,
            navigation = request.navigation,
            conversationActionsAvailable = request.conversationActionsAvailable,
            settingsAvailable = request.settingsAvailable,
            compact = request.compact,
            width = request.width,
            onNew = request.onNew,
            onSelect = request.onSelect,
            onPin = request.onPin,
            onDelete = request.onDelete,
            onSettings = request.onSettings,
            onDestination = request.onDestination,
        )
    }
}
