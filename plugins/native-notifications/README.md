# Native scheduled-task notifications

`provider.notifications.platform` publishes `KcodeScheduledTaskNotifications`.
Its factory allocates native resources per mount, withdraws and joins owned calls,
and releases them. `consumer.schedules.application` requires this service and
becomes Pending when absent; custom/headless composition explicitly uses the
foreground-only factory with no OS notifications.

Android owns channel creation and posting. The channel id and user preferences
persist; each mount has a unique notification tag so overlapping runtimes cancel
only their own deliveries. Calls read Activity SDK lifecycle on Main and use the
application Context for NotificationManager. Channel display name is configuration,
so APK code does not assume resource ids from the host. Native composition supplies
the localized label. The host owns notification permission prompting.

Desktop queries a borrowed window accessor on EDT and lazily owns one TrayIcon.
Withdrawal removes its listener/icon and flushes the image; a stale action cannot
reopen a retired window. The app only binds/unbinds the window reference.

API version 11 removes notification objects from UI requests, the old shared
native implementation and foreground singleton; notification methods are suspending.
Tests cover allocation cancellation, Pending/rebinding, call cleanup before release,
actual Windows SystemTray inventory/listeners, and isolated APK posting/cancelling
real Android notifications, including two overlapping generations. Android device
tests use temporary adopted POST_NOTIFICATIONS identity and only grant/app-op the
standalone test package, which AGP uninstalls; production permission state is untouched.


Native external entries are `AndroidNativeNotificationsPlugin` (String channel name) and
`DesktopNativeNotificationsPlugin` (Unit). Acquire OS inputs with
`PluginHostInputs.current(ctx, effect)`; the host supplies Android or desktop inputs per
runtime and Cordis revokes each mount's lease after native resource cleanup. Persisted
external packages can therefore restore without storing Activity/Frame objects or using
reflection. Default native composition uses the same entries and respects explicit profile
overrides. The permission request policy still requires its separate plugin migration.


Android generation foreground allowance is an independent policy plugin,
`AndroidGenerationForegroundPlugin`. Its JSON string configuration contains localized
channelName/title/text; it injects `KcodeGeneration` and leases native host inputs.
Positive task counts acquire one generic foreground execution lease, overlapping tasks
share it, and zero tasks or withdrawal releases it. Disabling this policy does not cancel
responses. Scope cleanup joins observers and reports lease cleanup failures. Notification
channel/content, launcher action, and activation policy live here rather than in the app.

The SDK's stable manifest Service contains only native promotion and lease coordination.
It accepts framework Notification objects and the declared dataSync service type. Tokens
are per acquisition; releasing an old token updates the current notification while other
tokens remain, and only the last release stops the Service. Acquisition failure removes
its token. Android background-start denial preserves the existing behavior of allowing
model responses to continue without obtaining the foreground allowance.

Actual APK policy tests use a recording host to check response count transitions,
withdrawal without response cancellation, reenable, generation replacement, overlapping
runtime retirement, and revoked root inputs. A separate real native Service test verifies
foreground notification flags and last-lease removal. Only that standalone test APK is
temporarily granted POST_NOTIFICATIONS/app-op access, and background-start permission is
adopted then dropped in finally. Its test notification requests immediate display; the
product keeps the previous platform-default display policy. These results do not prove
production user consent or visible notification/chooser/overlay interaction.


Notification startup consent now belongs to `AndroidNotificationPermissionPlugin`, an
optional `policy.notifications.permission.android` UI contribution. The native host
installs it when supplied a generic `AndroidPermissionHost`. It publishes an application
effect; activation itself does not open a permission prompt, so manifest publication and
committed UI precede the request. Each mount attempts once. Theme/layout removal and
reattachment do not repeat the prompt; reenable creates a new policy generation. Withdrawal
removes the effect, cancels/joins its owned wait, and makes captured renderers inactive.

MainActivity registers only RequestMultiplePermissions and forwards named results through
`AndroidPermissionRequestBroker`. The broker serializes requests on Main, skips granted
permissions, retains an outstanding OS dialog after caller cancellation, and waits for its
completion before launching the next permission. Unrelated/stale named results are ignored;
an empty system result denies the current request. Activity destruction closes the broker,
releases its callbacks/context, and completes pending waits as denied. The OS dialog itself
is not dismissible through this contract. Host-input leases revoke new calls independently.

Real APK Compose CPU tests cover disabled activation, no request before rendering, private
renderer identity, waiting for cancellation cleanup, stale renderers, reenable, once-per-mount
behavior across UI removal, uninstall, and root-input revocation. Broker instrumentation
covers cancellation/late names, empty results, host close, already-granted permissions, and
launcher failure. These tests use synthetic permission results and do not consent to any
production permission prompt or establish visible interaction on a locked device.

Native distribution exports three independent logical packages from this module. The
dual-target `provider.notifications.platform` selects `DesktopNativeNotificationsPlugin`
on desktop and `LocalizedAndroidNativeNotificationsPlugin` on Android, both with Unit
configuration and native host-input leases. The Android-only packages
`policy.notifications.permission.android` and `provider.generation.foreground.android`
use `AndroidNotificationPermissionPlugin` and `LocalizedAndroidGenerationForegroundPlugin`.
Desktop staging skips their payloads. Implementations are absent from the host classpath;
neutral foreground execution primitives and manifest components remain in the SDK/host.
The host must still declare its permissions; an imported package cannot grant them.

API 59 localized channel/foreground configuration uses the neutral catalog language
projection on committed settings. Language schema/defaults stay in the localization feature;
notification providers do not read fixed settings fields or decode feature JSON.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.notifications.platform
- policy.notifications.permission.android
- provider.generation.foreground.android

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
