package ai.meteor.kcode

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal object WebScheduledTaskPlatformHost : ScheduledTaskPlatformHost {
    override fun isAppInForeground(): Boolean = documentIsVisible()

    override fun showTriggeredNotification(title: String, body: String) {
        showBrowserNotification(title, body)
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => typeof document !== 'undefined' && document.visibilityState === 'visible'")
private external fun documentIsVisible(): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(title, body) => { if (typeof Notification !== 'undefined' && Notification.permission === 'granted') new Notification(title, { body }); }")
private external fun showBrowserNotification(title: String, body: String)
