package ai.meteor.kcode

import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import ai.meteor.kcode.createAndroidKoogChatRuntime
import ai.meteor.kcode.plugin.api.AndroidPermissionRequestBroker
import ai.meteor.kcode.plugin.api.AndroidConfirmationDialogHost
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val settingsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SettingsChangedAction) recreate()
        }
    }

    private val confirmationDialogs = AndroidConfirmationDialogHost {
        (application as KcodeApplication).hostActivities.current() ?: this
    }
    private lateinit var agentRuntime: KcodeAgentRuntime
    private val permissionBroker: AndroidPermissionRequestBroker = AndroidPermissionRequestBroker(
        context = { this },
        launch = { permission -> permissionLauncher.launch(arrayOf(permission)) },
    )
    private val permissionLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> permissionBroker.onResult(results) }
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (::agentRuntime.isInitialized) {
                lifecycleScope.launch { agentRuntime.conversationOverlayController?.setHostForeground(true) }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            if (::agentRuntime.isInitialized) {
                lifecycleScope.launch { agentRuntime.conversationOverlayController?.setHostForeground(false) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this, settingsChanged, IntentFilter(SettingsChangedAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            var pendingRuntime: KcodeAgentRuntime? = null
            var adopted = false
            try {
                val runtime = createAndroidKoogChatRuntime(
                    activity = this@MainActivity,
                    settingsBackedShell = true,
                    permissionHost = permissionBroker,
                    settingsBackedInteraction = true,
                    confirmationDialogs = confirmationDialogs,
                )
                pendingRuntime = runtime
                currentCoroutineContext().ensureActive()
                runtime.conversationOverlayController?.setHostForeground(
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                )
                agentRuntime = runtime
                (application as KcodeApplication).attachContent(checkNotNull(agentRuntime.applicationContent))
                adopted = true
                ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
                setContent {
                    checkNotNull(agentRuntime.applicationContent).Render(
                        ApplicationHostOptions(
                            shellSettingsAvailable = true,
                            toolPermissionControlsAvailable = true,
                        ),
                    )
                }
            } finally {
                if (!adopted) withContext(NonCancellable) { pendingRuntime?.close() }
            }
        }
    }

    override fun onDestroy() {
        confirmationDialogs.close()
        permissionBroker.close()
        unregisterReceiver(settingsChanged)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        if (::agentRuntime.isInitialized) {
            agentRuntime.applicationContent?.let { (application as KcodeApplication).detachContent(it) }
            (application as KcodeApplication).retireRuntime(agentRuntime)
        }
        super.onDestroy()
    }

}
