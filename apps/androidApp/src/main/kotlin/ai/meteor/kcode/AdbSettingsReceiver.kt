package ai.meteor.kcode

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.meteor.kcode.settings.createAndroidAppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AdbSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                require(intent.action == ConfigureSettingsAction) { "Unsupported action" }
                val update = intent.toAdbSettingsUpdate()
                val settingsStore = createAndroidAppSettingsStore(context)
                val applied = settingsStore.load().applyAdbSettingsUpdate(update)
                settingsStore.save(applied.settings)
                applied.changedFields.joinToString(
                    prefix = "Updated kcode settings: ",
                    separator = ", ",
                )
            }.onSuccess { message ->
                pendingResult.setResultCode(Activity.RESULT_OK)
                pendingResult.setResultData(message)
            }.onFailure { failure ->
                pendingResult.setResultCode(Activity.RESULT_CANCELED)
                pendingResult.setResultData(
                    "Unable to update kcode settings: ${failure.message.orEmpty().lineSequence().first().take(256)}",
                )
            }
            pendingResult.finish()
        }
    }
}

private const val ConfigureSettingsAction = "ai.meteor.kcode.action.CONFIGURE_SETTINGS"

private fun Intent.toAdbSettingsUpdate(): AdbSettingsUpdate {
    val unknownExtras = extras?.keySet().orEmpty() - AdbSettingExtra.entries.mapTo(mutableSetOf()) { it.key }
    require(unknownExtras.isEmpty()) { "Unknown setting: ${unknownExtras.sorted().joinToString()}" }
    fun stringExtra(extra: AdbSettingExtra): String? = if (hasExtra(extra.key)) {
        requireNotNull(getStringExtra(extra.key)) { "${extra.key} must be passed with --es" }
    } else {
        null
    }
    return AdbSettingsUpdate(
        modelProvider = stringExtra(AdbSettingExtra.ModelProvider),
        model = stringExtra(AdbSettingExtra.Model),
        modelApiKey = stringExtra(AdbSettingExtra.ModelApiKey),
        modelEndpoint = stringExtra(AdbSettingExtra.ModelEndpoint),
        modelRegion = stringExtra(AdbSettingExtra.ModelRegion),
        modelDeployment = stringExtra(AdbSettingExtra.ModelDeployment),
        modelApiVersion = stringExtra(AdbSettingExtra.ModelApiVersion),
        dashscopeRegion = stringExtra(AdbSettingExtra.DashscopeRegion),
        temperature = stringExtra(AdbSettingExtra.Temperature),
        searchProvider = stringExtra(AdbSettingExtra.SearchProvider),
        searchApiKey = stringExtra(AdbSettingExtra.SearchApiKey),
    )
}

private enum class AdbSettingExtra(val key: String) {
    ModelProvider("model-provider"),
    Model("model"),
    ModelApiKey("model-api-key"),
    ModelEndpoint("model-endpoint"),
    ModelRegion("model-region"),
    ModelDeployment("model-deployment"),
    ModelApiVersion("model-api-version"),
    DashscopeRegion("dashscope-region"),
    Temperature("temperature"),
    SearchProvider("search-provider"),
    SearchApiKey("search-api-key"),
}
