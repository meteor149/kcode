package ai.meteor.kcode.plugin

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.plugin.ui.api.NavigationDestination
import ai.meteor.kcode.plugin.ui.api.NavigationPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.plugin

fun navigationPlugin(destination: NavigationDestination): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.ui.navigation.${destination.id}", "builtin", "built-in", setOf("uiSlots", "navigation")),
    plugin<Unit>(name = "navigation-${destination.id}", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
        collect(ctx.require(KcodeUiSlots.Key).registerNavigation(destination))
    },
    Unit,
)

private object ChatNavigationRenderer : UiRenderer<NavigationPageRequest> {
    @Composable
    override fun Render(request: NavigationPageRequest) {
        request.slots.chat?.let { renderer -> key(renderer) { renderer.Render(request.chat) } }
    }
}

private object ArtifactsNavigationRenderer : UiRenderer<NavigationPageRequest> {
    @Composable
    override fun Render(request: NavigationPageRequest) {
        request.slots.artifacts?.let { renderer -> key(renderer) { renderer.Render(request.artifacts) } }
    }
}

object DefaultChatNavigationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "navigation-chat"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerNavigation(
                NavigationDestination(
                    id = "chat",
                    order = 0,
                    icon = KcodeIconAsset.Chat,
                    title = { text(UiText.Chats) },
                    renderer = ChatNavigationRenderer,
                    handlesConversations = true,
                    isAvailable = { it.chat != null },
                ),
            ),
        )
    }
}

object DefaultArtifactsNavigationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "navigation-artifacts"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerNavigation(
                NavigationDestination(
                    id = "artifacts",
                    order = 10,
                    icon = KcodeIconAsset.Artifacts,
                    title = { text(UiText.Artifacts) },
                    renderer = ArtifactsNavigationRenderer,
                    isAvailable = { it.artifacts != null },
                ),
            ),
        )
    }
}

fun defaultNavigationPlugins(): List<KcodePluginMount> = listOf(
    kcodePlugin(PluginDescriptor("provider.ui.navigation.chat", "builtin", "built-in", setOf("uiSlots", "navigation")), DefaultChatNavigationPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.navigation.artifacts", "builtin", "built-in", setOf("uiSlots", "navigation")), DefaultArtifactsNavigationPlugin, Unit),
)
