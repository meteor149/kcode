package ai.meteor.kcode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.AndroidHostActivities
import android.app.Application

class KcodeApplication : Application() {
    internal lateinit var hostActivities: AndroidHostActivities
        private set

    override fun onCreate() {
        super.onCreate()
        hostActivities = AndroidHostActivities(this)
    }

    private val retirement = AndroidRuntimeRetirement()
    internal fun retireRuntime(runtime: KcodeAgentRuntime) = retirement.retire(AgentRuntimeOwner(runtime::close))
    private class ContentSlot(val value: ApplicationContent)
    private val content = MutableStateFlow<ContentSlot?>(null)
    internal fun attachContent(value: ApplicationContent) { content.value = ContentSlot(value) }
    internal fun detachContent(value: ApplicationContent) {
        content.update { if (it?.value === value) null else it }
    }
    internal suspend fun updateSettings(update: SettingsUpdate): AppliedSettingsUpdate {
        val active = content.value ?: withTimeoutOrNull(5_000) { content.filterNotNull().first() }
        return checkNotNull(active) { "Open kcode before configuring settings" }.value.updateSettings(update)
    }
    internal suspend fun modelCatalog() = content.value?.value?.modelCatalog() ?: ModelCatalogSnapshot()
}
