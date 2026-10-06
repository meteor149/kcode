# Committed Profile archives

`ProfileArchiveExchange` is the native backend for exchanging a frozen committed or
historical Profile together with its exact code archives. It preserves ordered Bundle
definitions and user operations instead of flattening them into a single effective tree.
The native backend lives in `plugins/profiles`. SDK 74 exposes `importArchive` and
`exportArchive`, and the optional Profile screen supplies separate complete-archive actions.

## Export

The caller supplies a `ProfileManagement`, committed/historical `ProfileTarget`, expected
repository revision, destination file, package directory, native host and verified resolver.
Export uses the management instance's host-owned feature review selection. It does not accept
caller-supplied approval callbacks. Unknown fields and codecs remain denied, including stored
values hidden by later layers. Frozen Bundle versions need not be semantic versions: their
original values remain inside the portable document.

The reviewed `profile.json` excludes deployment paths, runtime snapshots, machine/launch
overlays and business data. It resets data scopes to independent Profile scopes. Every
committed code archive is reverified and snapshotted with its locked digest before packing.
Export stages a complete archive and checks repository revision after review and packaging,
then copies into the destination filesystem and atomically replaces the destination. Failure
before replacement preserves an existing output. Locked code inputs cannot be overwritten,
including existing filesystem aliases. Temporary files are owned and released on failure or
cancellation. Filesystem publication and repository authority are separate operations; the
revision check does not lock the repository through the final filesystem rename.

## Container

The `.kprofile` suffix is a convention. Content and SHA-256 establish the format:

- Cordis `plugin.json` has ID `profile.<profile.json-sha256>` and transport version `1.0.0`.
- Exactly one data variant uses runtime `kcode-profile-archive`, minimum version `1`,
  artifact and entry point `profile.json`, with empty runtime metadata and variant extensions.
- The only root extension is `ai.meteor.kcode.profile-archive`, containing format version 1
  and package ID/path records using the Bundle archive metadata schema.
- Root dependencies equal the portable lock's complete package ID/version set.
- Files contain exactly `profile.json` and declared `code/<archive-sha256>.bin` archives.
- Embedded code records match portable lock digests. Definitions are bounded to 2 MiB
  with strict UTF-8; transport/code limits and path validation use the shared Cordis writer.

The data variant supports native hosts. This does not promise that embedded code has a
compatible variant for every platform.

## Owned SDK exchange and native files

`importArchive(ProfileArchiveImport(reference, id, revision, name))` accepts one caller-owned
temporary archive path/digest. The caller retains it through return. Management checks
revision and create-only identity before native preparation and again at draft publication.
Import does not change active composition. Metadata commands work in Ready and RecoveryRequired.

`exportArchive(ProfilePortableExport(target, revision)) { reference -> ... }` reviews and
packages frozen intent before calling the consumer. The host owns the temporary file and
directory, retaining them only through the suspending consumer. Success, consumer failure,
cancellation and bridge withdrawal release the lease. The consumer must copy the bytes before
returning and cannot treat the locator as a persisted capability. Callbacks must not unload
their own provider or close the runtime. Stale bridge clients reject these calls.

The UI captures import revision before the native picker. Selected inputs stream into owned
temporary files under the existing 512 MiB selection limit and are deleted after command
completion. Export streams the host lease while rechecking its digest, bounded to 512 MiB.
Desktop writes a sibling temporary file and atomically replaces the selected destination.
Android uses OpenDocument/CreateDocument and streams through the document provider; provider
writes may be partial on failure and do not offer atomic rollback. Cancelled Android picker
slots stay reserved until the old OS callback arrives. Dirty editors and concurrent file
actions are rejected. Imported drafts are selected for editing without automatic preview or
activation. JSON and Bundle actions retain their existing independent formats.

## Import preparation

`prepare(input, id, displayName, builtinModules)` verifies the outer archive, manifest,
content identity, frozen definition and exact embedded code declarations. The native resolver
verifies every embedded archive, actual code identity, dependency graph, target variant and
SDK/framework compatibility. Code ID, version, archive digest and dependencies must match the
source lock. A self-consistent outer manifest cannot authorize false embedded code identities.

Only after native verification does preparation generate the target host's deployment lock.
The selected variant/ABI may differ from the source host because the same exact archive can
contain both Desktop and Android code. Missing compatible code is an error; there is no code
download, arbitrary version substitution or host-resource fallback. Plain portable JSON import
keeps its existing strict variant/ABI lock semantics.

Preparation compiles the original frozen layers, verifies selected code using the fresh lock,
and returns a `PortableProfileDocument` under the requested identity with independent data
scopes. It may populate immutable caches but does not mount product providers, publish a draft,
advance history or select a Profile. A trusted caller can publish it through revision-checked
`ProfileManagement.importPortable`; activation remains separate. After draft publication,
input and outer data deployments are unnecessary. The verified code cache remains required.

## Verification

Metadata tests cover historical layers, create-free preparation, default-denied export,
revision/draft rejection with unchanged output, incorrect digests and unsupported metadata.
The actual Desktop test exports a two-Bundle Localization generation, prepares it in a new
receiver, imports the draft and starts from the receiver cache after outer deployment removal.
It also repacks a valid outer container with a false locked version and verifies refusal by
the real native code resolver.

SDK command tests cover create-only/revision conflicts, unchanged active composition, stale
clients, consumer failure/cancellation and temporary lease cleanup. The actual Desktop host
also closes while a consumer is suspended and proves cleanup completes; the consumer itself
cannot close its own host. UI tests cover captured revisions, draft selection, cancelled and
withdrawn pickers, lease lifetime and binary digest refusal. The private Android Profile UI
opens/cancels the complete-archive OS picker and confirms no authority/runtime mutation.

`AndroidProfileArchiveExchangeTest` consumes that actual Desktop fixture. Pass instrumentation
arguments `profileArchive=/data/local/tmp/kcode-profile-archive-fixture.kprofile` and
`profileArchiveSha256=<digest>` after pushing the Desktop test's output. Without arguments,
ordinary instrumentation runs explicitly skip this cross-host case. The test streams the
shell-owned transfer file into its isolated cache, verifies unchanged code identities and a
different native variant, publishes a draft, removes source/outer files, starts Android code
and exports the same frozen layers with its Android lock. This establishes backend transfer
and real APK execution, not OS file-dialog acceptance, Shizuku authorization or root execution.

AndroidProfileFileRoundtripTest separately passes actual DocumentsUI selections through the
shipped private settings page: complete archive export to Downloads, saved profile.json
comparison and reimport as a draft without changing the active generation. This establishes
selected-file acceptance for that Android page; Desktop dialogs, multi-Bundle import selections
and full application navigation still require separate checks. See verification.md.
