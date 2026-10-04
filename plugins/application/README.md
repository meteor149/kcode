# Application providers

This module supplies the default `applicationUi` provider. Web containers, settings,
history and artifact repositories live in their independent provider modules. Definitions
live in `plugins:api`; platform container implementations remain in their provider modules.

Each provider owns one Cordis service. Profiles can replace or disable it by its
stable inventory id, and consumers declare service dependencies rather than
importing these implementations. `ApplicationUiPlugin` accepts an
`DefaultUiRenderer` from `plugins:default-ui-api`. Product `KcodeApp`/`KcodeMain` orchestration lives in this
module; `plugins:api` retains neutral contracts and `libraries:ui` supplies reusable controls and design contracts. Platform lifecycle callbacks are supplied as
`ApplicationHostOptions`. The default plugin prepares its feature projection and render frame for the
Compose host, preserving UI state when unrelated tools change. The kernel root contract is `ApplicationRenderer` / `ApplicationFrame`; it requires none of the default application services. Only frame preparation can use its generic service lookup. Frame preparation precedes manifest persistence; a failed frame restores the previous composition instead of persisting the candidate.

The model sees tools from the configured consumers. Read-only artifact providers
do not expose the save tool. Missing provider dependencies suspend consumers
until the provider returns. Replacing storage switches the interface used by
the UI; migrating existing stored data is the provider author's responsibility.

The application renderer is separate from typed UI contributions in
`plugins:ui-pages`: layout, sidebar, chat, Artifacts, settings, overlays and theme
have reversible slots. Setting forms live in `plugins:ui-settings`. Composition
and installed-package state are persisted by their independent storage plugins.
See [the architecture guide](../../docs/plugin-architecture.md) for lifecycle rules
and [the verification guide](../../docs/verification.md) for acceptance criteria and limitations.

The settings, history and artifact provider modules own their respective operation facades.
Factory-backed repositories allocate on mount and release resources after calls settle;
explicit borrowed repositories remain caller-owned. Previously obtained facades reject
calls after withdrawal. Durable data survives resource closure. History forwarding preserves
atomic batch writes, ID allocation and scheduling semantics. Read-only artifact providers
remain distinct from mutable providers.
The Web provider publishes its operation-owned facade here.
Web controller disposal first cancels/joins calls, then invokes its idempotent
`closeAll` resource contract under non-cancellable cleanup. Default cleanup attempts
all listed containers and aggregates failures. A missing controller exposes no fake
capability. Native teardown stops desktop preview servers and their workers, joins
managed browser processes, and closes Android WebViews and capability-bridge jobs.
Android also rejects queued launches whose session was already removed. Native
Activity verification currently requires the connected device to be awake/unlocked;
see the completion audit for the current verification state.

The native `provider.ui.compose` mount uses `DefaultApplicationUiPlugin`, which
requires settings, history, sessions, generation and modelSettings. Removing these providers puts the default
UI Pending. `ApplicationUiPlugin` remains available for custom renderers with their
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
