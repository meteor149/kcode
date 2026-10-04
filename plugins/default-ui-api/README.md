# Default UI contracts

This module is the optional contract library for the shipped Compose application. It defines
its layout/sidebar/page/navigation requests, typed presentation slots, settings sections,
message and tool presenters, and application effects. Their implementation belongs to
application, ui-pages, ui-settings and the corresponding feature plugins.

These types are not kernel UI requirements. A different root can use only plugins:api:
ApplicationRenderer prepares an ApplicationFrame from a short-lived ApplicationServices
lookup. Its Cordis plugin declares required services; it chooses how absent optional services
are displayed. Native hosts call that frame without adding default layout or theme wrappers.

KcodeUiContributions accepts plugin-defined UiSlotKey values or UiContributionSource
projections. Withdrawing one contribution cancels and joins its pending projection calls before
its Disposable completes; replacement keys remain available while old cleanup finishes.
The default UiSlotsServicePlugin publishes its own ApplicationUiSlots projection
under DefaultUiSnapshotKey. Only default UI consumers interpret that projection. Native overlay
lifecycle bridges transport UiContributionsSnapshot; the default overlay adapter explicitly
selects the default projection. The kernel has no built-in sidebar/page/navigation inventory.

The host shares the ai.meteor.kcode.plugin.ui.api namespace to preserve the optional default
contract identity across actual JAR/APK loaders. Product renderer classes remain private.
Current package ABI is API 34; older packages must be rebuilt. The default application starts
as a builtin plugin composition; this does not auto-install every feature as an external file.
The default UI contracts export `libraries:ui` because their requests use its icon and
opaque glass-state types. The component library itself has no dependency on these
contracts, core API or shared. It supplies reusable code; each root, theme and page
plugin still determines its own rendering and composition lifetime.

`KcodeUiSlots.register(key, renderer)` and `resolve(key)` use the key's contract ID,
matching the kernel contribution registry. Reconstructing a typed key with the same
ID therefore resolves the same slot; the contract owner must keep its value type consistent.
`snapshot()` prepares the shipped application's fixed slot vocabulary and ordered feature
lists. Custom slot owners use `resolve` during frame preparation and retain the resolved value,
not a live registry lookup in composition. Each registration returns an independent,
idempotent disposer: repeated cleanup cannot remove a replacement, even when it reuses
the same renderer instance. Cleanup completes in a non-cancellable context.

Verify these contracts with `gradlew.bat :plugins:default-ui-api:desktopTest`.
