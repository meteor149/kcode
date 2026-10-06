# Profile management UI

Complete Profile archive actions use SDK 74 `importArchive`/`exportArchive`. Import captures
revision before single-file selection and stages an owned input. Export consumes a host-owned
temporary lease during native saving. Binary streaming checks the digest and a 512 MiB bound;
Desktop replaces atomically, while Android provider writes can be partial on failure.
Cancellation publishes no draft or success. See [committed archives](../../docs/profile-archives.md).

The Bundle archive action uses SDK 73 `importBundles`, captures revision before native
multi-file selection and creates an isolated draft without activation. Picker order is
the initial Bundle order. Inputs are streamed into owned temporary files (up to sixteen
archives and 512 MiB total), then removed after the command completes or fails. Dirty
editors and duplicate file actions are rejected. Android cancelled slots remain reserved
until the OS callback returns. See [Bundle archives](../../docs/profile-bundle-archives.md).

`DefaultProfileUiPlugin` is the optional `provider.ui.settings.profiles` package entry.
Its child contributes a `profiles` settings section when both `KcodeProfiles` and
`KcodeUiSlots` are available. Missing services suspend that child without imposing
management or default layouts on independent roots. Provider withdrawal removes the
contribution and cancels/joins its queries and command observers.

The screen supports catalogue selection, creation, cloning, JSON definition editing,
revision-checked draft saving, explicit discard, verified preview, effective tree and
diagnostics, module catalogue, history selection, activation and confirmed deletion.
Deletion confirmation captures the target and authority revision. Unsaved edits retain
their original revision across refreshes; stale publication cannot overwrite another writer.
Names, Bundle order, operations and scopes can be edited in the definition document.
Creation and cloning use isolated business-data scopes and do not copy business data.

File import uses a new Profile ID and optional display name, reads a bounded UTF-8 JSON
document, and creates an isolated draft through the host's revision-checked exchange client.
It does not prepare packages or activate the imported tree. Preview and activation remain
explicit. Unsaved editor changes must be saved or discarded before file exchange.
Export is available for committed and historical selections; host review completes before
the save picker opens. Unknown opaque configuration is rejected until its feature policy
is wired. Android uses system document providers; Desktop uses the native file dialog and
an atomic replacement in the selected directory. Picker cancellation publishes no success
or Profile mutation. UI withdrawal cancels/joins its pending picker operation.
Android document-provider writes may be partial on provider failure and are not atomic;
no rollback of an external provider's file is claimed. Blocking provider I/O cancellation
is cooperative and may delay withdrawal until that I/O returns.

Tree forms support plugin/group insertion, explicit position/parent selection, configuration
codecs, reparenting/order, module replacement, enable/disable, removal and service scope editing.
They append SDK operations and save a revision-checked draft, then reload the host preview;
they do not compose an independent tree or change the running Profile. Bundle controls add,
remove and reorder explicit references, and the name field can rename the selected draft.
Forms reject duplicates, unavailable modules, invalid codec values and ancestry cycles before
saving. Historical sources require cloning before structured edits. Unsaved raw documents
must be saved or discarded before using forms. A saved definition remains visible if its
subsequent preview query fails. Late asynchronous UI callbacks quietly withdraw after closure.

Accepted activation belongs to the native host. Removing this UI cancels its observer,
not the accepted command. Explicit cancellation uses the retained command handle. Recent
command status comes from the host stream, so a reconstructed screen can see the result.
The package owns English/Chinese resource defaults, follows the app language, and permits
custom translation catalogue overrides. Spacing and colors use shared design tokens.
The string XML generates a private default dictionary during compilation. Profile labels
do not use the shared Compose Android resource reader, which resolves through the host
context rather than the feature APK. This keeps defaults independent of host resources
on both platforms while preserving app-language selection and catalogue overrides.

New native templates select `kcode.default-ui` version 2, which includes this module.
Existing committed generations retain their frozen version 1 Bundle definitions; they
do not silently acquire new UI. Explicit Profile editing can insert
`provider.ui.settings.profiles` when that module is available.

Unsaved raw edits defer section return, system back and sheet dismissal before the exit
animation. Users can save the revision-checked draft and leave, discard to the saved document,
or continue editing. Invalid documents, authority conflicts and changed confirmation targets
retain the editor; saving never activates the draft. Forced provider withdrawal bypasses the
confirmation and cancels owned work without running pending navigation.
Saving keeps the confirmation open until its durable result. Confirmation actions share
one measured column so narrow layouts do not clip the final action.

Android instrumentation renders the actual private settings/Profile/theme APKs in a native
window and checks editing, system-back confirmation, invalid-save retention, live English/
Chinese switching, save/leave and discard/leave against durable metadata. It also checks
confirmation text bounds and captures a screenshot. These semantic actions do not establish
touch/keyboard accessibility or complete form/activation acceptance. Import/export,
independent recovery UI, desktop rendering and broader device acceptance remain outstanding.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.ui.settings.profiles

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
