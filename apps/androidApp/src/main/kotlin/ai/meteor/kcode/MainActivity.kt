package ai.meteor.kcode

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
    private lateinit var scheduledTaskPlatformHost: AndroidScheduledTaskPlatformHost
    private lateinit var agentRuntime: KcodeAgentRuntime
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (::agentRuntime.isInitialized) {
                agentRuntime.conversationOverlayController?.setHostForeground(true)
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            if (::agentRuntime.isInitialized) {
                agentRuntime.conversationOverlayController?.setHostForeground(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        scheduledTaskPlatformHost = AndroidScheduledTaskPlatformHost(applicationContext)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val settingsStore = createAndroidAppSettingsStore(applicationContext)
        val historyRepository = createAndroidConversationHistoryRepository(applicationContext)
        val imageSaver = AndroidConversationImageSaver(this)
        val shellExecutionMode = AtomicReference(ShellExecutionMode.App)
        val toolPermissionMode = AtomicReference(ToolPermissionMode.Ask)
        agentRuntime = createAndroidKoogChatRuntime(
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        setContent {
            KcodeApp(
                chatService = agentRuntime.chatService,
                generationRunner = (application as KcodeApplication).generationRunner,
                webContainerController = agentRuntime.webContainerController,
                artifactRepository = agentRuntime.artifactRepository,
                settingsStore = settingsStore,
                historyRepository = historyRepository,
                imageSaver = imageSaver,
                shellSettingsAvailable = true,
                toolPermissionControlsAvailable = true,
                scheduledTaskPlatformHost = scheduledTaskPlatformHost,
                onShellExecutionModeChanged = shellExecutionMode::set,
                onToolPermissionModeChanged = toolPermissionMode::set,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        scheduledTaskPlatformHost.setForeground(true)
    }

    override fun onStop() {
        scheduledTaskPlatformHost.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        if (::agentRuntime.isInitialized) {
            agentRuntime.conversationOverlayController?.close()
        }
        super.onDestroy()
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
