package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.plugin.api.ToolPermissionSettingsPolicy
import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.chat.ConversationCommandSnapshot
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.api.harness.HarnessSessionStore
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ConversationImageRenderer
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.chat.UnavailableScheduledTasks
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.chat.ConversationSessionFactory
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.settings.ModelSettingsPolicy
import ai.meteor.kcode.plugin.api.ShellModeSettingsPolicy
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.webcontainer.WebContainerController
import androidx.compose.runtime.Composable
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** A UI provider receives interfaces, never platform implementations or a mutable Cordis context. */
data class ApplicationViewServices(
    val chatService: ChatService,
    val settingsStore: AppSettingsStore,
    val historyRepository: ConversationHistoryRepository?,
    val artifactRepository: ArtifactRepository?,
    val webContainerController: WebContainerController?,
    val uiSlots: ApplicationUiSlots = ApplicationUiSlots(),
    val goalSessions: GoalSessionFactory = UnavailableGoalSessions,
    val schedules: ScheduledTaskCoordinator = UnavailableScheduledTasks,
    val conversationSessions: ConversationSessionFactory? = null,
    val conversationExecution: ConversationExecution? = null,
    val modelCatalog: ModelCatalogSnapshot = ModelCatalogSnapshot(),
    val commands: ConversationCommandSnapshot = ConversationCommandSnapshot(),
    val generationRunner: ChatGenerationRunner? = null,
    val modelSettingsPolicy: ModelSettingsPolicy? = null,
    val shellModeSettingsPolicy: ShellModeSettingsPolicy? = null,
    val toolPermissionSettingsPolicy: ToolPermissionSettingsPolicy? = null,
)

/** Default application's feature projection; it is not the kernel root UI contract. */
fun interface DefaultUiRenderer {
    @Composable
    fun Render(services: ApplicationViewServices, options: ApplicationHostOptions)
}
