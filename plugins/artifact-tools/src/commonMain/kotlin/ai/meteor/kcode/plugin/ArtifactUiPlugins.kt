package ai.meteor.kcode.plugin

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.pages.artifact.ArtifactPageActions
import ai.meteor.kcode.plugin.pages.artifact.ArtifactsPage
import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.ArtifactsPageRequest
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.NavigationDestination
import ai.meteor.kcode.plugin.ui.api.NavigationPageRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.component.KcodeIconAsset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

internal class DefaultArtifactsPageRenderer(private val actions: ArtifactPageActions) : UiRenderer<ArtifactsPageRequest> {
    @Composable
    override fun Render(request: ArtifactsPageRequest) {
        ArtifactsPage(
            actions = actions,
            repository = request.repository,
            webContainerController = request.webContainerController,
            compact = request.compact,
            onMenu = request.onMenu,
            modifier = request.modifier,
        )
    }
}


object DefaultArtifactsUiPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "provider.ui.artifacts"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeArtifacts.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val actions = ArtifactPageActions()
        effect.collect { actions.close() }
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.Artifacts, DefaultArtifactsPageRenderer(actions)))
    }
}

private object ArtifactsNavigationRenderer : UiRenderer<NavigationPageRequest> {
    @Composable
    override fun Render(request: NavigationPageRequest) {
        request.artifacts?.let { artifacts ->
            request.slots.artifacts?.let { renderer -> key(renderer) { renderer.Render(artifacts) } }
        }
    }
}

object DefaultArtifactsNavigationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "navigation-artifacts"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key, KcodeArtifacts.Key)
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
