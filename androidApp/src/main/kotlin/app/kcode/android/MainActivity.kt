package app.kcode.android

import android.os.Bundle
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.kcode.KcodeApp
import app.kcode.shared.R
import app.kcode.createAndroidKoogChatService
import app.kcode.settings.createAndroidAppSettingsStore
import app.kcode.history.createAndroidConversationHistoryRepository
import app.kcode.export.AndroidConversationImageSaver
import app.kcode.settings.ShellExecutionMode
import app.kcode.settings.ToolPermissionMode
import app.kcode.ToolApprovalRequest
import app.kcode.ToolCallApprover
import java.util.concurrent.atomic.AtomicReference
import app.kcode.search.WebSearchConfiguration
import app.kcode.search.WebSearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = createAndroidAppSettingsStore(applicationContext)
        val historyRepository = createAndroidConversationHistoryRepository(applicationContext)
        val imageSaver = AndroidConversationImageSaver(this)
        val shellExecutionMode = AtomicReference(ShellExecutionMode.App)
        val toolPermissionMode = AtomicReference(ToolPermissionMode.Ask)
        val chatService = createAndroidKoogChatService(
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
                chatService = chatService,
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
            suspendCancellableCoroutine { continuation ->
                var dialog: AlertDialog? = null
                dialog = AlertDialog.Builder(this@MainActivity)
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
