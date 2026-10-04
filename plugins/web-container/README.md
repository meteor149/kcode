# Web-container plugin

The common tools and provider implementation live here. Native engines live under
`ai.meteor.kcode.plugin.webcontainer.native`, outside the shared host ABI.

* Desktop entry: `DesktopNativeWebContainerPlugin`, configured with an absolute workspace
  path. Each mount owns its sessions, server, Chromium process, CDP client and profile.
* Android entry: `AndroidNativeWebContainerPlugin`, configured with `Unit`. It acquires
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

See the [Web container guide](WEB_CONTAINER_GUIDE.md) for preview, native capability
and agent lifecycle behavior. The former `:extensions:webContainer` module has been removed.
