package ai.meteor.kcode

import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import ai.meteor.kcode.plugin.recovery.ProfileHostContent
import ai.meteor.kcode.plugin.KcodeProfileHost
import ai.meteor.kcode.plugin.api.AndroidPermissionRequestBroker
import ai.meteor.kcode.plugin.api.AndroidConfirmationDialogHost
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

open class MainActivity : ComponentActivity() {
    protected open val managementEntry: Boolean = false
    private val settingsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SettingsChangedAction) recreate()
        }
    }

    private val confirmationDialogs = AndroidConfirmationDialogHost {
        (application as KcodeApplication).hostActivities.current() ?: this
    }
    private lateinit var agentRuntime: KcodeAgentRuntime
    private var profileHost: KcodeProfileHost? = null
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
                val host = createAndroidProfileHost(
                    profileId = intent?.getStringExtra("profile"),
                    activity = this@MainActivity,
                    settingsBackedShell = true,
                    permissionHost = permissionBroker,
                    settingsBackedInteraction = true,
                    confirmationDialogs = confirmationDialogs,
                    managementOnly = managementEntry,
                )
                val runtime = host.runtime
                pendingRuntime = runtime
                currentCoroutineContext().ensureActive()
                profileHost = host
                runtime.conversationOverlayController?.setHostForeground(
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                )
                agentRuntime = runtime
                if (!managementEntry) {
                    (application as KcodeApplication).apply {
                        registerPrimaryProfileHost(host)
                        attachContent(checkNotNull(agentRuntime.applicationContent))
                    }
                }
                adopted = true
                ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
                setContent {
                    val primaryHost by (application as KcodeApplication).primaryProfileHost.collectAsState()
                    ProfileHostContent(host,
                        ApplicationHostOptions(
                            shellSettingsAvailable = true,
                            conversationSettingsControlsAvailable = true,
                        ),
                        resources.configuration.locales[0].language,
                        managementMode = managementEntry,
                        managementClient = if (managementEntry) primaryHost?.profileCommands else null,
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
            if (!managementEntry) {
                (application as KcodeApplication).apply {
                    profileHost?.let(::unregisterPrimaryProfileHost)
                    agentRuntime.applicationContent?.let(::detachContent)
                }
            }
            (application as KcodeApplication).retireRuntime(agentRuntime)
        }
        super.onDestroy()
    }

}

class PluginManagerActivity : MainActivity() {
    override val managementEntry: Boolean = true
}
