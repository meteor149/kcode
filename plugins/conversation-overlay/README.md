# Conversation overlay plugins

`core.conversation-overlays` provides `KcodeConversationOverlays`, an optional
presentation registry consumed by the Agent Loop. Each new turn resolves the
current controller. An empty registry returns no overlay; it creates no built-in
controller. Disabling the registry makes its declared consumers Pending.

`provider.conversation-overlay.platform` owns a bounded controller factory. Android
creates its WindowManager/Compose implementation during plugin activation; desktop
has no default system overlay. Providers may be disabled, replaced, or loaded from
an APK using the same shared contracts. Host foreground state survives provider
and registry generations. The stable native host entry resolves the current
registry and rejects startTurn when the capability is absent.

The Provider cancels and joins operations, finishes retained turns, and awaits
controller closure. Cleanup attempts every turn and the backend, reports aggregated
failures, and caches the result for concurrent/repeated close. Captured controller
and turn handles reject operations after withdrawal. Canceled allocation discards
a late turn. The native Android close disposes the window and joins its scope,
including permission polling and current or previously canceled recomposer jobs.

System-window UI uses the same committed theme/message/tool contributions as the
application. UI contribution withdrawal updates the composition, and absent theme
removes the window. Compose rendering checks run on desktop and real Android,
rather than Android JVM stubs. Android host runtime initialization is suspending so
Provider activation can dispatch to Main. An unadopted runtime is closed if Activity
initialization is canceled.

API version 11 includes the earlier change that makes controller foreground and close operations suspending; plugins
compiled against the previous ABI must be rebuilt. The implementation namespace is
child-first, and overlay/turn/message contracts preserve host identity.

Verification:

```sh
./gradlew :plugins:conversation-overlay:desktopTest :plugins:agent-loop:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidConversationOverlayProviderTest
```

The CPU instrumentation covers isolated APK replacement, native allocation/scope
closure, and assembling/closing the native host factory on Main. Compose contribution
rendering runs separately in the module's test APK:

```sh
./gradlew :plugins:conversation-overlay:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.overlay.ConversationOverlayPresentationTest
```

 Foreground window rendering is separately exercised by the application's
`ConversationOverlayLifecycleTest`; it requires an unlocked, awake device and overlay
permission. CPU lifecycle results do not prove window rendering.

Android defaults now mount `AndroidNativeConversationOverlayPlugin`, the same entry
available to an external APK. It acquires an effect-owned `AndroidPluginHostInputs`
lease and resolves the registry's read-only `uiSlots` flow. The host no longer passes
an Activity/controller factory for the default overlay. The flow follows committed
UI changes and remains shared across controller generations; each controller retains
only application context and owns its windows, permission polling, and Compose scope.
The legacy factory override remains available to custom compositions.

The actual native-entry APK test verifies private implementation class loading with
shared SDK identity, theme withdrawal/restoration through the projection, disable and
reenable, uninstall, joined controller scopes, rejected stale turns, and root-input
release. It keeps the host foreground and does not establish visible system-window
rendering or user permission consent.
