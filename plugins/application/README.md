# Application providers

This module supplies the default `applicationUi` provider. Settings and history
repositories live in their independent provider modules. Definitions live in `plugins:api`.

Each provider owns one Cordis service. Profiles can replace or disable it by its
stable inventory id, and consumers declare service dependencies rather than
importing these implementations. `ApplicationUiPlugin` accepts an
`DefaultUiRenderer` from `plugins:default-ui-api`. Product `KcodeApp`/`KcodeMain` orchestration lives in this
module; `plugins:api` retains neutral contracts and `libraries:ui` supplies reusable controls and design contracts. Platform lifecycle callbacks are supplied as
`ApplicationHostOptions`. The default plugin prepares its feature projection and render frame for the
Compose host, preserving UI state when unrelated tools change. The kernel root contract is `ApplicationRenderer` / `ApplicationFrame`; it requires none of the default application services. Only frame preparation can use its generic service lookup. Frame preparation precedes manifest persistence; a failed frame restores the previous composition instead of persisting the candidate.

The model sees tools from the configured consumers. Missing provider dependencies suspend consumers
until the provider returns. Replacing storage switches the interface used by
the UI; migrating existing stored data is the provider author's responsibility.

The application renderer is separate from typed UI contributions in
`plugins:ui-pages`, `plugins:ui-messages`, `plugins:ui-shell`, and `plugins:ui-theme`: conversation pages, messages, layout/settings and theme
have reversible slots. Setting forms and validation live in their feature packages. The application forwards generic draft saves and keeps rendering when the model settings provider is absent. Composition
and installed-package state are persisted by their independent storage plugins.
See [the architecture guide](../../docs/plugin-architecture.md) for lifecycle rules
and [the verification guide](../../docs/verification.md) for acceptance criteria and limitations.

The settings and history provider modules own their respective operation facades.
Factory-backed repositories allocate on mount and release resources after calls settle;
explicit borrowed repositories remain caller-owned. Previously obtained facades reject
calls after withdrawal. Durable data survives resource closure. History forwarding preserves
atomic batch writes, ID allocation and scheduling semantics.

The native `provider.ui.compose` mount uses `DefaultApplicationUiPlugin`, which
requires settings infrastructure. History, sessions, generation and localization are optional;
settings remain usable when these feature providers are absent. Compact layouts show the sidebar
when no navigation page is available, preserving access to settings. `ApplicationUiPlugin` remains available for custom renderers with their
own dependency choices. The default mount owns settings operations across its UI
sessions, invalidates rendering on withdrawal and joins pending writes.

`ApplicationSettingsSession` separates form drafts from committed execution settings.
Load/save cancellation propagates. Writes serialize, stale queued requests are skipped,
and only the latest successful request updates runtime configuration and platform
shell/permission modes. Failure retains the active configuration and exposes a settings
error. Slow startup reads cannot overwrite newer edits. UI forms request stored-setting
changes; they do not change platform execution mode directly. The application consumes
the committed generation service and `ModelSettingsPolicy`; it does not create a default
runner or retain a shared model configuration strategy. Initial empty SDK snapshots do
not select a model before the storage provider loads its persisted values or private defaults.

The default root does not require Generation. Missing Generation leaves the sidebar
and settings usable, skips generation-dependent effects and prepares no chat request.
Restoring Generation resumes chat without recreating the settings session.

When the selected route needs chat and Generation is missing, the default layout
keeps navigation visible on compact screens so settings remains reachable.

History and Sessions are optional root capabilities as of API 50. Their withdrawal
removes conversation projections, chat requests and dependent effects; settings
and unrelated navigation survive. Session leases are disposed independently, and
the settings session is keyed only by its storage and root owner. Temporary route
withdrawal does not overwrite the user selection, which can return on recovery.

API 51 removes Localization from the root requirements. The default root owns a
rendering catalog that prefers the borrowed translation catalog and uses active
feature-provided English defaults for missing keys or absent localization. It never
publishes a substitute Localization service or closes the borrowed catalog. Saved
language remains unchanged. Disposed rendering catalogs reject strict calls.

API 52 submits UI field differences through the transaction-capable store published
by `KcodeSettings`. Differences merge into the latest durable snapshot, preserving
unrelated command changes and independently keyed credentials. Pending UI differences
survive skipped intermediate requests; only a successful latest request publishes
the merged snapshot as execution configuration. This storage transaction does not
yet route UI patches through feature command validation.

API 53 removes the concrete exporter projection from default root services and chat
requests. Export contributes prepared actions and notices through the generic page
presentation protocol; root and chat know no export menu or state implementation.

API 58 passes generic conversation settings drafts/submission to feature contributions.
The permission button, schema and mutations live in `interaction`; the root only
resolves optional configuration policy for host notification. Removing Interaction removes
its control while preserving unrelated pages, settings and persisted feature documents.

API 61 prepares the settings store from `KcodeSettings.mutationStore`. The application
contains no feature namespace list or validation rules: it merges draft differences in
the shared transaction, and the settings infrastructure invokes registered feature owners
before durable commit. A failed validation retains the committed execution configuration
and editable draft; unavailable owners reject writes of their namespace without blocking
other settings. Feature registration lifetimes cover the durable save.

The root consumes the UI, model catalog and conversation command snapshots already prepared by
the runtime. It prepares navigation presenters once before committing a frame and retains their
renderers. Conversation-specific request construction lives in the conversation page provider,
while this module coordinates navigation, the shared conversation workspace and settings drafts.
