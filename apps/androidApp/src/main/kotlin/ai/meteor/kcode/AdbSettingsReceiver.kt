package ai.meteor.kcode

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.meteor.kcode.settings.SettingsUpdate
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
                val update = intent.toSettingsUpdate()
                val applied = (context.applicationContext as KcodeApplication).updateSettings(update)
                context.sendBroadcast(Intent(SettingsChangedAction).setPackage(context.packageName))
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

/** Transport accepts feature identities; mounted contributions decide which fields exist. */
internal fun Intent.toSettingsUpdate(): SettingsUpdate = SettingsUpdate(
    extras?.keySet().orEmpty().associateWith { key ->
        requireNotNull(getStringExtra(key)) { "$key must be passed with --es" }
    },
)

internal const val SettingsChangedAction = "ai.meteor.kcode.action.SETTINGS_CHANGED"
