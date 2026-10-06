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

The default catalog includes `core.conversation-overlays` as a desktop/Android
`.kplugin` and `provider.conversation-overlay.platform` as an Android-only `.kplugin`.
The registry uses `NativeConversationOverlaysServicePlugin` with Unit configuration.
API 42 adds `ConversationOverlayHostState.bind/current`: the runtime binds its borrowed
foreground state and committed UI projection before loading the registry. These values
survive registry replacement without keeping a private implementation in the host.
External plugins must be rebuilt against the matching SDK ABI.

Android defaults load `AndroidNativeConversationOverlayPlugin` from the catalog's
verified APK payload. It acquires an effect-owned `AndroidPluginHostInputs`
lease and resolves the registry's read-only `uiSlots` flow. The host no longer passes
an Activity/controller factory for the default overlay. The flow follows committed
UI changes and remains shared across controller generations; each controller retains
only application context and owns its windows, permission polling, and Compose scope.
The legacy factory override remains available to custom compositions.

The actual registry/provider archive test verifies private implementation class loading with
shared SDK identity, preserved foreground state and projection across registry replacement,
theme withdrawal/restoration through the projection, disable and
reenable, uninstall, joined controller scopes, rejected stale turns, and root-input
release. It keeps the host foreground and does not establish visible system-window
rendering or user permission consent.

The desktop/device presentation tests compile their shared scenario from `src/uiTestFixtures`.
Android instrumentation does not depend across source-set trees on `commonTest`; its test
runtime dependencies are explicit. Shared ownership tests remain in the regular common tree.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- core.conversation-overlays
- provider.conversation-overlay.platform

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
