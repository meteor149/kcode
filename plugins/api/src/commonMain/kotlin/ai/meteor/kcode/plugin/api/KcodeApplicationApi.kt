package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.chat.ConversationCommandSnapshot
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.api.harness.HarnessSessionStore
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ConversationImageRenderer
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
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.withTransactions
import ai.meteor.kcode.settings.withMutationValidation
import ai.meteor.kcode.settings.RegisteredSettingsMutations
import ai.meteor.kcode.settings.SettingsMutationRegistry
import androidx.compose.runtime.Composable
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

class KcodeSettings(ctx: Context, store: AppSettingsStore) : Service<Unit>(ctx, Key) {
    private val owner = PluginOperationOwner("Settings transactions")
    val store = store.withTransactions(owner)
    private val mutationRegistry = RegisteredSettingsMutations()
    val mutations: SettingsMutationRegistry = mutationRegistry
    /** UI/alternative-root writes require the live feature owner through durable commit. */
    val mutationStore: AppSettingsStore = this.store.withMutationValidation(mutationRegistry)
    init {
        ctx.effect(dispose = {
            owner.requireCanClose()
            try { mutationRegistry.close() } finally { owner.close() }
        }, label = "settings transactions")
    }
    companion object { val Key = ServiceKey<KcodeSettings>("settings") }
}

class KcodeConversationImageSaving(ctx: Context, val saver: ConversationImageSaver) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeConversationImageSaving>("conversationImageSaving") }
}

class KcodeConversationExport(ctx: Context, val exporter: ConversationExporter) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeConversationExport>("conversationExport") }
}

class KcodeConversationImageRendering(ctx: Context, val renderer: ConversationImageRenderer) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeConversationImageRendering>("conversationImageRendering") }
}

class KcodeHistory(ctx: Context, val repository: ConversationHistoryRepository) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeHistory>("history") }
}

class KcodeConversationExecution(ctx: Context, val executor: ConversationExecution) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeConversationExecution>("conversationExecution") }
}

class KcodeSessions(
    ctx: Context,
    val factory: ConversationSessionFactory,
    /** Reserved full log seam; null means the current row-backed UI projection is the only capability. */
    val eventStore: HarnessSessionStore? = null,
) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSessions>("sessions") }
}

class KcodeScheduledTaskNotifications(ctx: Context, val host: ScheduledTaskPlatformHost) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeScheduledTaskNotifications>("scheduledTaskNotifications") }
}

class KcodeSchedules(ctx: Context, val coordinator: ScheduledTaskCoordinator) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeSchedules>("schedules") }
}

class KcodeGoals(ctx: Context, val sessions: GoalSessionFactory) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeGoals>("goals") }
}

/** A published root can draw any structure. It receives no mandatory application services. */
fun interface ApplicationFrame {
    @Composable
    fun Render(options: ApplicationHostOptions)
}

/** The UI plugin resolves its own dependencies and prepares its committed render frame. */
interface ApplicationServices {
    val uiContributions: UiContributionsSnapshot? get() = null
    val modelCatalog: ModelCatalogSnapshot? get() = null
    val conversationCommands: ConversationCommandSnapshot? get() = null

    /** Only valid during snapshot preparation; retain the resolved capabilities, not this lookup. */
    operator fun <T> get(key: ServiceKey<T>): T?
}

fun interface ApplicationRenderer {
    suspend fun snapshot(services: ApplicationServices): ApplicationFrame?
}

class KcodeApplicationUi(ctx: Context, val renderer: ApplicationRenderer) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeApplicationUi>("applicationUi") }
}
