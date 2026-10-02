package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.webcontainer.WebContainerController
import androidx.compose.runtime.Composable
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeSettings(ctx: Context, val store: AppSettingsStore) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSettings>("settings") }
}

class KcodeHistory(ctx: Context, val repository: ConversationHistoryRepository) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeHistory>("history") }
}

class KcodeArtifacts(ctx: Context, val repository: ArtifactRepository) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeArtifacts>("artifacts") }
}

class KcodeWebContainers(ctx: Context, val controller: WebContainerController?) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeWebContainers>("webContainers") }
}

/** A UI provider receives interfaces, never platform implementations or a mutable Cordis context. */
data class ApplicationViewServices(
    val chatService: ChatService,
    val settingsStore: AppSettingsStore,
    val historyRepository: ConversationHistoryRepository,
    val artifactRepository: ArtifactRepository,
    val webContainerController: WebContainerController?,
)

fun interface ApplicationRenderer {
    @Composable
    fun Render(services: ApplicationViewServices, options: ApplicationHostOptions)
}

class KcodeApplicationUi(ctx: Context, val renderer: ApplicationRenderer) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeApplicationUi>("applicationUi") }
}
