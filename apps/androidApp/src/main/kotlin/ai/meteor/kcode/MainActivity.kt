package ai.meteor.kcode

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.meteor.kcode.KcodeApp
import ai.meteor.kcode.activeAndroidWebContainerActivity
import ai.meteor.kcode.createAndroidKoogChatRuntime
import ai.meteor.kcode.export.AndroidConversationImageSaver
import ai.meteor.kcode.history.createAndroidConversationHistoryRepository
import ai.meteor.kcode.settings.createAndroidAppSettingsStore
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.shared.R
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.tools.search.WebSearchConfiguration
import ai.meteor.kcode.tools.search.WebSearchProvider
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val settingsStore = createAndroidAppSettingsStore(applicationContext)
        val historyRepository = createAndroidConversationHistoryRepository(applicationContext)
        val imageSaver = AndroidConversationImageSaver(this)
        val shellExecutionMode = AtomicReference(ShellExecutionMode.App)
        val toolPermissionMode = AtomicReference(ToolPermissionMode.Ask)
        val runtime = createAndroidKoogChatRuntime(
            activity = this,
            modeProvider = { shellExecutionMode.get() },
            permissionModeProvider = { toolPermissionMode.get() },
            toolCallApprover = ToolCallApprover(::confirmToolCall),
            webSearchConfigurationProvider = {
                settingsStore.load().let {
                    WebSearchConfiguration(
                        provider = WebSearchProvider.fromCode(it.webSearchProvider),
                        brightDataApiKey = it.webSearchApiKey,
                        exaApiKey = it.exaSearchApiKey,
                    )
                }
            },
        )
        setContent {
            KcodeApp(
                chatService = runtime.chatService,
                webContainerController = runtime.webContainerController,
                settingsStore = settingsStore,
                historyRepository = historyRepository,
                imageSaver = imageSaver,
                shellSettingsAvailable = true,
                toolPermissionControlsAvailable = true,
                onShellExecutionModeChanged = shellExecutionMode::set,
                onToolPermissionModeChanged = toolPermissionMode::set,
            )
        }
    }

    private suspend fun confirmToolCall(request: ToolApprovalRequest): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@withContext false
            val dialogActivity = activeAndroidWebContainerActivity() ?: this@MainActivity
            suspendCancellableCoroutine { continuation ->
                var dialog: AlertDialog? = null
                dialog = AlertDialog.Builder(dialogActivity)
                    .setTitle(getString(R.string.tool_confirmation_title, request.name))
                    .setMessage(
                        getString(
                            R.string.tool_confirmation_message,
                            request.description.ifBlank { request.name }.take(2_048),
                            request.input.take(8_192),
                        ),
                    )
                    .setPositiveButton(R.string.tool_confirmation_allow) { _, _ ->
                        if (continuation.isActive) continuation.resume(true)
                    }
                    .setNegativeButton(R.string.tool_confirmation_deny) { _, _ ->
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .setOnCancelListener {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .show()
                continuation.invokeOnCancellation { runOnUiThread { dialog?.dismiss() } }
            }
        }
}
