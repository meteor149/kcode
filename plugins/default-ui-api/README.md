# Default UI contracts

This module is the optional contract library for the shipped Compose application. It defines
its layout/sidebar/page/navigation requests, typed presentation slots, settings sections,
message and tool presenters, and application effects. Their implementation belongs to
application, ui-pages and the corresponding feature plugins. Registry/bridge implementations
live separately in ui-contributions and default-ui-bridge.

These types are not kernel UI requirements. A different root can use only plugins:api:
ApplicationRenderer prepares an ApplicationFrame from a short-lived ApplicationServices
lookup. Its Cordis plugin declares required services; it chooses how absent optional services
are displayed. Native hosts call that frame without adding default layout or theme wrappers.

KcodeUiContributions accepts plugin-defined UiSlotKey values or UiContributionSource
projections. Withdrawing one contribution cancels and joins its pending projection calls before
its Disposable completes; replacement keys remain available while old cleanup finishes.
The `default-ui-bridge` UiSlotsServicePlugin publishes its own ApplicationUiSlots projection
under DefaultUiSnapshotKey. Only default UI consumers interpret that projection. Native overlay
lifecycle bridges transport UiContributionsSnapshot; the default overlay adapter explicitly
selects the default projection. The kernel has no built-in sidebar/page/navigation inventory.

The neutral registry implementation lives in `ui-contributions`; withdrawing the default bridge
does not withdraw projections used by alternative roots. Concrete pages live in `ui-pages`.

The host shares the ai.meteor.kcode.plugin.ui.api namespace to preserve the optional default
contract identity across actual JAR/APK loaders. Product renderer classes remain private.
Current package ABI is API 60; older packages must be rebuilt. The default application starts
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

Settings pages receive only persisted settings, persistence feedback, generic save/dismiss
callbacks and registered sections. Model/search/Shell-specific state and callbacks belong
to feature renderers. Section visibility is composable so feature renderers can consume
prepared frame catalogs. Retained sections stop rendering after withdrawal and reject
stale save callbacks. `settingsSectionPlugin` is an SDK helper for custom contributions.

API 46 permits absent `ApplicationViewServices.artifactRepository` and
`NavigationPageRequest.artifacts`. Artifact page/navigation consumers declare their own
repository requirement; withdrawing that feature keeps the default root and settings usable.

API 48 adds generic `ConversationDecorationPosition.Header` and `AboveComposer` anchors,
with `Header` as the source default. `ConversationPageContext.hazeState` is an optional
borrowed visual context; alternative consumers may omit it. These data-class constructor
changes require rebuilding older external packages. Both new types remain under the
already shared default UI namespace; feature presenters remain independently loaded.

API 49 makes `NavigationPageRequest.chat` nullable. The default root and non-chat
navigation continue rendering without Generation; chat and generation-dependent
effects withdraw until that capability returns. The default UI namespace remains
shared, while page/root implementations remain private.

API 50 makes `ApplicationViewServices.historyRepository` and
`NavigationPageRequest.conversationSession` optional. The shell prepares settings
and navigation without conversation providers; chat and effects receive their
required interfaces only while those capabilities exist. This changes shared
contracts within the existing default UI namespace, without exporting private code.

API 51 adds revocable `UiTextDictionary` snapshots and `SettingsSection.texts`.
A settings section registers/releases its own defaults atomically with its UI.
Independent contributions may reuse text keys only with identical default values;
new keys should be namespaced. Failed section registration releases newly allocated
defaults, and old disposal never removes a replacement registration.

API 53 allows a conversation presenter to prepare a list of projections once per page,
including generic `HeaderActions` content. Page context carries optional selection IDs
and clear/before-action callbacks. Default root and chat requests no longer project an
exporter; export services and presentation remain feature-owned. Rebuild external UI
packages for the changed constructors and presenter return contract.

API 57 adds optional `ApplicationViewServices.shellModeSettingsPolicy`, prepared from the
current execution policy. Default root host notifications resolve the committed snapshot
through this feature-owned projection. Missing configuration capability remains optional;
the root does not decode the fixed execution-mode scalar. Rebuild external default UI
consumers for the constructor ABI change.

API 58 removes permission-specific mode/availability/callback fields from `ChatPageRequest`.
`SettingsEditorProjection` carries a prepared draft and generic submit boundary;
`ConversationPageContext.settingsEditor` and `ComposerActions` let feature packages own
composer configuration controls. Missing editor capability means no configuration controls.
`ApplicationViewServices.toolPermissionSettingsPolicy` is an optional host notification
projection, resolved by the feature rather than by root storage decoding.
