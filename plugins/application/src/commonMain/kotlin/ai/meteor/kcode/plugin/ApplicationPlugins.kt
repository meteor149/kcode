package ai.meteor.kcode.plugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.KcodeApp
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationViewServices
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.webcontainer.WebContainerController
import androidx.compose.runtime.Composable
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object SettingsProviderPlugin : Plugin<AppSettingsStore> {
    override val name = "kcode-settings-platform"
    override suspend fun apply(ctx: Context, config: AppSettingsStore, effect: EffectScope) {
        KcodeSettings(ctx, config)
    }
}

object HistoryProviderPlugin : Plugin<ConversationHistoryRepository> {
    override val name = "kcode-history-platform"
    override suspend fun apply(ctx: Context, config: ConversationHistoryRepository, effect: EffectScope) {
        KcodeHistory(ctx, config)
    }
}

object ArtifactsProviderPlugin : Plugin<ArtifactRepository> {
    override val name = "kcode-artifacts-platform"
    override suspend fun apply(ctx: Context, config: ArtifactRepository, effect: EffectScope) {
        KcodeArtifacts(ctx, config)
    }
}

object WebContainersProviderPlugin : Plugin<WebContainerController?> {
    override val name = "kcode-web-containers-platform"
    override suspend fun apply(ctx: Context, config: WebContainerController?, effect: EffectScope) {
        KcodeWebContainers(ctx, config)
    }
}

/** Default application UI is a provider; profiles may replace it with a different renderer. */
object ApplicationUiPlugin : Plugin<ApplicationRenderer> {
    override val name = "kcode-application-ui"
    override suspend fun apply(ctx: Context, config: ApplicationRenderer, effect: EffectScope) {
        KcodeApplicationUi(ctx, config)
    }
}

object DefaultApplicationRenderer : ApplicationRenderer {
    @Composable
    override fun Render(services: ApplicationViewServices, options: ApplicationHostOptions) {
        KcodeApp(
            chatService = services.chatService,
            generationRunner = options.generationRunner,
            webContainerController = services.webContainerController,
            artifactRepository = services.artifactRepository,
            settingsStore = services.settingsStore,
            historyRepository = services.historyRepository,
            imageSaver = options.imageSaver,
            shellSettingsAvailable = options.shellSettingsAvailable,
            toolPermissionControlsAvailable = options.toolPermissionControlsAvailable,
            scheduledTaskPlatformHost = options.scheduledTaskPlatformHost,
            onShellExecutionModeChanged = options.onShellExecutionModeChanged,
            onToolPermissionModeChanged = options.onToolPermissionModeChanged,
        )
    }
}
