# Web-container plugin

The common tools, provider and default overlay implementation live here. Native engines live under
`ai.meteor.kcode.plugin.webcontainer.native`, outside the shared host ABI.

`feature.web-container` owns its native controller, model tools and optional default
overlay in one install/enable boundary. Internal consumers declare their own dependencies;
missing UI/localization services suspend presentation only. With no controller, no empty
tool contribution or overlay is registered.

* Desktop entry: `DesktopWebContainerFeaturePlugin`, configured with an absolute workspace
  path. Each mount owns its sessions, server, Chromium process, CDP client and profile.
* Android entry: `AndroidWebContainerFeaturePlugin`, configured with `Unit`. It acquires
  leased Android host inputs and generic SDK windows; each mount owns its sessions,
  WebViews, workspace response streams and capability workers.

Android external generations read resources from a unique, checksum-verified, read-only
APK copy. Android caches resource managers by path; importing the same original APK into
two runtimes must not share ownership of an AssetManager. Shutdown closes calls and
windows, waits for capability workers and window observers, then releases the resource
manager and deletes its copy. Built-ins borrow their deployment's resource table.

Neutral Web contracts and their parsing tests live in `:plugins:api`;
`:plugins:platform-android` owns the OS FileProvider adapter, manifest and shared paths. Product
content uses the SDK's manifest Activity; an external APK does not require registering its
own Activity in the installed application. Plugin API 14 removes the old shared native
implementation and debug-script ABI, so older packages must be rebuilt.

Actual JAR/APK composition tests verify isolation, interaction, cleanup, stale references
and restoration. Android's native bridge is restricted to the local preview origin.

The overlay and its owned polling/restore/close actions have moved from `ui-pages` into
this module, together with `WebContainersUiLifecycleTest`. Missing controller/UI/localization
services suspend presentation independently. The default page module consumes only the
optional typed overlay slot. Retained actions reject calls after withdrawal; disposal
joins owned polling and button work. Legacy low-level entry classes remain available.

Native builds distribute one `feature.web-container` `.kplugin` release, with desktop
and Android variants. The provider's desktop configuration preserves the native workspace
path; Android uses Unit configuration and SDK host windows. WebKit remains private to the
APK. Core/Core KTX and versioned-parcelable are SDK peers: legacy `android.support`
parcelizers must agree with the host FileProvider/window adapters on Core type identity.
Shared window and lifecycle contracts come from the SDK. The provider and consumer implementations are
excluded from native host runtime dependencies.

The former `provider.web-containers.platform`, `consumer.tools.web-container` and
`provider.ui.web-containers` releases migrate together. Recovery retains the old group
for surviving external dependencies and suppresses aggregate activation until migration
is possible; disabled choices and failed-commit rollback are preserved.

Caller-supplied controllers use `WebContainerFeaturePlugin` with the same owned consumers.
It rejects stale calls and preserves the existing close-all session cleanup behavior
without importing native engines. Real Chromium and WebView composition fixtures import
the provider archive through `AgentPluginManager.importPackages` before exercising
independent browsers, interactions and worker/resource teardown.

See the [Web container guide](WEB_CONTAINER_GUIDE.md) for preview, native capability
and agent lifecycle behavior. The former `:extensions:webContainer` module has been removed.
