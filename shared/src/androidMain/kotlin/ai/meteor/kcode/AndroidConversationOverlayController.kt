package ai.meteor.kcode

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.shared.R
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidConversationOverlayController(
    activity: Activity,
) : AgentConversationOverlayController {
    private val context = activity.applicationContext
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val messageIdsFlow = MutableStateFlow<List<Long>>(emptyList())
    private val messagesById = mutableStateMapOf<Long, ChatMessage>()
    private val turns = mutableListOf<AndroidConversationOverlayTurn>()
    private var hostForeground = true
    private var permissionRequested = false
    private var permissionJob: Job? = null
    private var activeTurn: AndroidConversationOverlayTurn? = null
    private var overlayView: ComposeView? = null
    private var overlayRecomposer: Recomposer? = null
    private var overlayRecomposerJob: Job? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn =
        withContext(Dispatchers.Main.immediate) {
            AndroidConversationOverlayTurn(initialMessages).also { turn ->
                turns += turn
                if (!hostForeground) reconcileOverlay()
            }
        }

    override fun setHostForeground(isForeground: Boolean) {
        mainHandler.post {
            if (hostForeground == isForeground) return@post
            hostForeground = isForeground
            permissionJob?.cancel()
            permissionJob = null
            if (isForeground) {
                turns.forEach { it.dismissedWhileBackground = false }
                activeTurn = null
                removeOverlay()
            } else {
                reconcileOverlay()
            }
        }
    }

    override fun close() {
        mainHandler.post {
            permissionJob?.cancel()
            permissionJob = null
            turns.clear()
            activeTurn = null
            removeOverlay()
            scope.cancel()
        }
    }

    private fun reconcileOverlay() {
        if (hostForeground) {
            activeTurn = null
            removeOverlay()
            return
        }
        val turn = turns.lastOrNull { !it.dismissedWhileBackground }
        if (turn == null) {
            permissionJob?.cancel()
            permissionJob = null
            activeTurn = null
            removeOverlay()
            return
        }
        activeTurn = turn
        if (Settings.canDrawOverlays(context)) {
            showOverlay(turn)
            return
        }
        removeOverlay()
        if (permissionRequested || permissionJob != null) return
        permissionRequested = true
        requestOverlayPermission()
        permissionJob = scope.launch {
            val granted = waitForConversationOverlayPermission(
                hasPermission = { Settings.canDrawOverlays(context) },
            )
            permissionJob = null
            if (granted && !hostForeground) {
                reconcileOverlay()
            }
        }
    }

    private fun showOverlay(turn: AndroidConversationOverlayTurn): Boolean {
        if (hostForeground || !Settings.canDrawOverlays(context)) return false
        activeTurn = turn
        replaceMessages(turn.messages)
        if (overlayView == null && !addOverlay()) return false
        return overlayView != null
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun addOverlay(): Boolean {
        val lifecycleOwner = OverlayLifecycleOwner()
        val recomposer = Recomposer(AndroidUiDispatcher.Main)
        val recomposerJob = scope.launch(
            context = AndroidUiDispatcher.Main,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composeView = ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setParentCompositionContext(recomposer)
        }
        lifecycleOwner.resume()
        composeView.setContent {
            val messageIds by messageIdsFlow.collectAsState()
            AndroidConversationOverlayContent(
                messageIds = messageIds,
                messageForId = messagesById::get,
                title = context.getString(R.string.conversation_overlay_title),
                closeDescription = context.getString(R.string.conversation_overlay_close_description),
                onDrag = ::moveOverlay,
                onClose = ::dismissOverlayForCurrentBackgroundPeriod,
            )
        }
        val bounds = windowManager.currentWindowMetrics.bounds
        val fullOverlayHeight = min(dp(420), bounds.height() - dp(120))
        val params = WindowManager.LayoutParams(
            min(dp(336), bounds.width() - dp(32)),
            fullOverlayHeight / 4,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(72)
            blurBehindRadius = dp(28)
        }
        return runCatching { windowManager.addView(composeView, params) }.onSuccess {
            overlayView = composeView
            overlayRecomposer = recomposer
            overlayRecomposerJob = recomposerJob
            overlayLifecycleOwner = lifecycleOwner
            layoutParams = params
        }.onFailure {
            composeView.disposeComposition()
            recomposer.cancel()
            recomposerJob.cancel()
            lifecycleOwner.destroy()
        }.isSuccess
    }

    private fun dismissOverlayForCurrentBackgroundPeriod() {
        activeTurn?.dismissedWhileBackground = true
        permissionJob?.cancel()
        permissionJob = null
        activeTurn = null
        removeOverlay()
    }

    private fun moveOverlay(horizontalChange: Float, verticalChange: Float) {
        val params = layoutParams ?: return
        params.x = (params.x - horizontalChange).toInt().coerceAtLeast(0)
        params.y = (params.y + verticalChange).toInt().coerceAtLeast(0)
        overlayView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
            view.disposeComposition()
        }
        overlayRecomposer?.cancel()
        overlayRecomposerJob?.cancel()
        overlayLifecycleOwner?.destroy()
        overlayView = null
        overlayRecomposer = null
        overlayRecomposerJob = null
        overlayLifecycleOwner = null
        layoutParams = null
        messageIdsFlow.value = emptyList()
        messagesById.clear()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun replaceMessages(messages: List<ChatMessage>) {
        val incomingIds = messages.map(ChatMessage::id)
        val incomingIdSet = incomingIds.toSet()
        messagesById.keys.filterNot(incomingIdSet::contains).forEach(messagesById::remove)
        messages.forEach { message -> messagesById[message.id] = message }
        messageIdsFlow.value = incomingIds
    }

    private inner class AndroidConversationOverlayTurn(initialMessages: List<ChatMessage>) : AgentConversationOverlayTurn {
        var messages = initialMessages
        var dismissedWhileBackground = false

        override suspend fun update(messages: List<ChatMessage>) = withContext(Dispatchers.Main.immediate) {
            this@AndroidConversationOverlayTurn.messages = messages
            if (activeTurn === this@AndroidConversationOverlayTurn && overlayView != null) {
                replaceMessages(messages)
            }
        }

        override suspend fun finish() = withContext(Dispatchers.Main.immediate) {
            turns.remove(this@AndroidConversationOverlayTurn)
            if (activeTurn === this@AndroidConversationOverlayTurn) {
                activeTurn = null
                removeOverlay()
                if (!hostForeground) reconcileOverlay()
            }
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle = registry
        override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
        }

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
