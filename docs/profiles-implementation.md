# Profile implementation

## Target and ownership

Profiles compose an empty plugin tree using ordered bundle layers, profile operations,
machine overrides and launch overrides. The interpretation is shared with Cordis Include.
Cordis owns generic composition and tree lifecycle. Kcode owns named profiles, package
resolution, data scopes, persistence, agent-turn boundaries and native management UI.

Portable definitions contain data only. Module allocation and ConfigValidator execution
belong to activation. Package IDs and entry IDs are separate identities. Bundle references
are versioned; unknown targets produce diagnostics rather than silently changing intent.

## Isolated development

- Kcode branch: `meteor/profiles`, managed worktree `kcode-profiles`.
- Cordis branch: `meteor/profile-composition`, sibling worktree `cordis-kotlin-profiles`.
- Build Kcode against the Cordis worktree using `-PcordisSource=<absolute path>`.
- Keep validation and commits at phase boundaries.

## Phases

1. Shared detached composition, patch diagnostics and sources; portable Profile models.
2. Observable transactional tree updates and Include candidate publication.
3. Profile repository, immutable package locks, generation commits and migration.
4. Default bundles, host selection and runtime application/switching.
5. Profile management, preview, import/export and host recovery UI.
6. Cross-platform package/lifecycle checks and user documentation.

## Outstanding invariants

Package preparation now uses the existing PluginPackageResolver with dependency archive
hints and verified closure locks. Entry IDs remain independent of release IDs, and group
structure is preserved for runtime binding. Profile configuration keeps Unit/scalar/JSON
codec identities; omitted configuration requests module defaults.

Legacy migration projects enable state, explicit uninstalls and external configuration into
profile operations. Bootstrap reads the last committed intent before a draft and retains
legacy snapshots after interrupted initial migration. ProfileCompositionSession implements
the existing composition store boundary and provides atomic definition/snapshot publication.

The runtime now accepts ProfileActivation through its managed startup facade. Native controllers
register modules without creating package-level instances; the Cordis tree retains instance
IDs, groups, per-instance configuration and code origins. Configurations, inventories, settling
and application frame preparation precede the atomic generation publisher. Legacy manager
enable mutations now use Profile transactions. Native imports, code upserts and removals
also route through joint module-generation and tree transactions. Full declaration editing,
Profile switching, external bundle import and management/recovery UI remain pending.

The shipped hosts now select this mode through ProfileStartupFactory. Native bundle layers,
explicit IDs, saved selection, machine configuration and committed restart are wired. Settings
and history scopes are bound on both platforms; desktop workspace scopes are bound as well.
Android workspace scope enforcement, Profile updating/switching, management UI and recovery
remain outstanding. Do not claim complete native Profile support from startup tests alone.

Phase-one validation passed: Cordis `:include:jvmTest` (14 tests), Kcode
`:plugins:profiles:desktopTest` (4 tests), using the Cordis composite source build.
No host or instrumentation validation has been claimed at this stage.

Cordis now provides `withTreeTransaction`, coordinated with module HMR. Include candidates
publish parsed content only after successful tree application, and failed files can be
retried unchanged. Durable publication runs without cancellation. The direct legacy
EntryGroup update remains best effort; product entry points must use managed transactions.

The JVM Profile repository atomically publishes definition, lock and runtime snapshot in
one committed document, separately from editable drafts. It checks generation conflicts and
serializes repository instances. Runtime integration must make that publication the final
fallible transaction step. Profile selection is a separate atomic record; switching still
needs an explicit recovery policy. Power-loss guarantees for directory metadata have not
been established; synced atomic files prove application-level publication, not all hardware
failure modes.

Phase-two validation: Loader, Include and HMR JVM suites including real JAR reload; nine
Profile compiler/repository desktop tests and Profile Android debug compilation. An existing
relocated-provider isolation test showed one transient PENDING/ACTIVE mismatch under the
combined build, then passed in isolation. Keep its settling behavior in the final lifecycle
audit; do not silently treat a targeted rerun as evidence for the entire suite.

Active agent turns must finish or be explicitly cancelled before application. Different
profiles switch by runtime shutdown/recreation initially. Durable provider data survives
withdrawal. Credentials and host paths do not belong in exported profile definitions.

Completion requires all phases, migration tests, real package loading evidence, desktop
and Android build validation and documentation. Phase-one tests alone are not completion.

The phase evidence below records behavior at each checkpoint. Later sections supersede
earlier pending-status statements; the [Profile guide](profiles.md) describes current behavior.

## Runtime startup phase evidence

Declarative startup validation passed against the Cordis worktree: 16 Profile compiler,
repository and preparation tests; 6 ProfileRuntimeTest scenarios; 14 existing runtime tests;
and 15 existing package integration tests. The new real-JAR scenario verifies private code
identity, SDK identity, per-instance configuration, code origin and independent cleanup.
The isolation scenario runs two configured providers and consumers in separate group realms.
Failure scenarios cover pre-allocation validation and resource cleanup after publication refusal.
Pending consumers and rejection of late startup were verified as discovered test cases in XML.

Android platform debug Kotlin compilation and the final runtime Android compilation passed.
No Android device Profile loading, full Profile package update or
Profile switching evidence is claimed. Public lazy mount export changes Plugin API to 64;
the exact existing shared exports cover the new member and external packages must be rebuilt.

## Native host startup phase evidence

The final phase build passed 57 tests: 21 Profile compiler/repository/preparation tests,
6 Profile runtime tests, 1 real native host test, 14 existing runtime tests and 15 package
integration tests. Desktop application compilation and Android APK assembly passed in the
same build against the Cordis worktree. The host test writes actual settings/history, closes
the runtime, selects a Profile and verifies persisted isolated data after restart; a corrupt
draft cannot displace the successful commit. Explicit native startup reads its separate data.

Format 2 commits freeze Bundle contents, always serialize their version, and retain code
locks on restart. Tests cover omitted-version format 1 recovery/upgrade and refusal to commit
incomplete frozen bundles. JSON null configuration survives serialization; machine overlays
apply to every matching instance without copying host paths into portable definitions.
These tests do not establish Android device persistence or complete mutation/switch semantics.

## Enable transaction phase evidence

The next phase build passed 61 tests: the same 21 Profile tests, 10 Profile runtime tests,
1 real native host test, 14 legacy runtime tests and 15 package integration tests. Desktop
compilation and Android APK assembly passed. Four new tests verify independent instance
withdrawal/recovery with a pending consumer, failed generation publication and successful
retry, rejection of an invalid batch before withdrawal, and launch-layer precedence over
saved Enable intent. The legacy manager routes individual and enable-only batch operations
through managed Profile tree transactions. Release imports/upserts/removals, configuration
changes, Profile switching and recovery UI remain pending.

## Package transaction phase evidence

Native controllers share prepared module-generation transactions. Runtime commands now
compile portable install/configure/enable/remove intent, retain required code dependencies,
bind candidate exports, validate all instances, settle the tree and prepare frames before
publishing a generation. Failure restores the old tree before releasing candidate code;
cancellation completes metadata/inventory/frame recovery without cancelling cleanup.
Unchanged releases retain bindings, and a code upgrade does not recreate a removed default
instance. Startup prefers saved releases over catalogue shadows, except explicit caller
overrides. Raw local descriptors stay in local snapshots rather than claiming archive locks.

The cancellation test exposed a Cordis lifecycle hang: explicit disposal did not cancel a
suspended apply. Cordis commit `ef00190` cancels the allocation child while the transition
owner completes suspending cleanup. Commit `f08918d` preserves provider exception identity
when crossing that child boundary. The final Cordis Core/Loader/Include/HMR JVM run passed
122 tests (60/35/16/11). Dependency-relocation behavior remains covered by existing tests.

The first Kcode run after the lifecycle fix passed all eight new real-JAR/archive package
transaction tests, including cancellation, publication/allocation failure, retry, independent
removal, unrelated resources, mixed batches, immutable releases and archive restart. An
existing exception-identity assertion failed and prompted the second Cordis fix. The final
complete Kcode phase build passed 71 tests: 23 Profile compiler/repository/preparation tests,
14 runtime tests, 1 native host test, 15 existing package integration tests, 10 Profile runtime
tests and 8 Profile package transaction tests. Desktop compilation and Android APK assembly
passed in the same build against Cordis commit `f08918d`. This does not establish Android
device package loading, complete Profile editing, switching or recovery UI.

## Immutable repository phase evidence

The JVM repository now stages immutable generation documents and atomically publishes a
single authority containing histories and current selection. Legacy committed/selection files
are imported once without rewriting them. Consistent authority reads include the selected
generation; joint commit/select compares both the repository revision and target generation.
Logical deletion withdraws the record without deleting provider data or adopting old files on
recreation. History includes published pointers only; staged/orphan files remain unreachable.

Seven new tests cover immutable history, selection advancement, revision conflicts, concurrent
switch publication, orphan/retry isolation, corrupt authority, deletion/recreation and legacy
selection migration. The existing format-upgrade and corrupt-generation tests now inspect
actual immutable files. Phase validation passed 78 tests: 30 Profile tests and the same 48
desktop runtime/native/package tests. Android platform compilation passed against the same
Cordis worktree. Runtime switching, recovery UI and Android device/power-loss behavior are
not established by this repository implementation.

## Prepared switch publication phase evidence

Profile sessions can stage candidate startup and definition updates until the host explicitly
publishes generation and selection together. Repeated staging advances once, rejection permits
retry, discard prevents later writes, and successful publication returns the session to normal
durable operation. Native staging captures the authority revision before target preparation and
does not save a migration draft. Four runtime tests cover allocated candidate invisibility,
refused publication/retry, concurrent authority conflict/discard and allocation cleanup. Two
repository/preparation tests cover target history and draft-free native staging. Phase validation
passed 84 tests (32 Profile tests and 52 desktop runtime/native/package tests), plus Android
platform compilation against Cordis `f08918d`. Stable host facades, old-runtime shutdown/recreation, turn admission and recovery UI
remain necessary before this becomes a complete live switching feature.

## Host coordinator phase evidence

`KcodeProfileHost` owns stable chat, plugin-manager, overlay and application facades around
replaceable complete runtimes. Admission closes before preparation, default switching refuses
active calls/overlay leases, and explicit cancellation joins admitted work before withdrawal.
The old locked preparation is retained before closure. Allocation or publication failure
reconstructs that exact committed intent without advancing history or changing selection.
Failed old closure or failed reconstruction enters `RecoveryRequired` and refuses execution.
Callback-initiated switch/closure is rejected. Post-publication cancellation leaves the
committed candidate live. Overlay withdrawal joins finishing leases and rejects stale updates;
foreground updates serialize with switches.

Eleven coordinator tests use real Cordis runtimes/providers and file-backed Profile sessions,
covering success, preflight failure, allocation/publication failure, cancellation/join, recovery
failure, old closure failure, callbacks, committed-boundary cancellation and overlay leases.
The phase build passed 95 tests (32 Profile and 63 desktop runtime/native/package tests), plus
Android platform compilation. Native factory integration, public management SDK, recovery UI
and Android device evidence are still required; this evidence does not claim those features.

## Desktop native host integration evidence

Desktop factories now retain only the module ID catalogue and host configuration, create fresh
host inputs for each product allocation, and return the coordinator's stable facades. Native
preparation supplies both target and old locked recipes before withdrawal. Existing factory
tests inspect admitted host diagnostics rather than retaining the raw product owner. Two new
native tests exercise live settings/history scope changes, stale store rejection, saved-selection
restart and failed target allocation followed by restoration of actual locked package providers.
Phase validation passed 97 tests (32 Profile and 65 desktop runtime/native/package tests),
including all three actual native factory tests. Desktop application compilation and Android
platform compilation passed against Cordis `f08918d`. Android factory integration, the exported
management SDK/UI and recovery UI remain outstanding; no Android device switching evidence is
claimed.

## Android native host integration evidence

Android factories now return the coordinator's stable facades and prepare target/restoration
recipes from the module ID catalogue. Every initial, candidate and restored allocation receives
fresh host inputs. Settings/history scopes use the existing MMKV/Room providers. Scoped file
tools, App shell defaults and Ubuntu `/workspace` share the configured app-private directory.
Unit configuration retains legacy paths. ADB rejects app-private scoped workspaces before
authorization rather than silently selecting shared data. Root paths are configured but actual
root authorization and execution are not established by this phase.

The phase build assembled the Android app and platform instrumentation APK, compiled the app's
instrumentation sources and passed the 97 related desktop tests (32 Profile and 65 native/runtime/
package tests), against Cordis `f08918d`. The dedicated instrumentation APK then ran all three
`AndroidProfileHostTest` cases successfully on an ARM64 API 36 physical device (`OK (3 tests)`).
The switch case requires the real APK Ubuntu provider on ARM64 and executes a command through
its scoped `/workspace` binding. It also verifies private filesystem implementation identity,
scoped settings/history/files, stale service rejection and persisted-selection restart. The
other cases verify failed target allocation restores the old locked state and history, and
scoped ADB rejection happens before authorization. Execution took 183.358 seconds.

Existing instrumentation owner-access adaptations were compiled, not run in this phase. Full
typed replacement, public Profile management SDK, declaration editing, external bundle import,
credential-safe export and management/recovery UI remain outstanding. This evidence does not
establish successful Shizuku/Root execution, full device-suite coverage or sudden power-loss
durability. See [verification](verification.md) for evidence boundaries.

## Public active-declaration editing evidence

Plugin API 65 moves portable definitions, entries, bundle references/declarations and operations
into `plugins/api`, under the existing shared `ai.meteor.kcode.plugin.api` export. Implementation
repositories, activations and native coordinators remain private. Entry serialization retains
omitted-versus-explicit-null meaning and now describes the optional configuration field in its
SDK descriptor. Older external packages require rebuilding against the generated API 65 ABI.

`AgentPluginManager.currentProfile` returns detached committed intent/generation. `editProfile`
compares active identity and generation, freezes incoming operations and compiles against the same
bundle/machine/launch stack as startup. Required package closure, validation, instance bindings,
tree/module rollback and publication use the existing manager transaction. Context operations
support partial inject/intercept/isolate replacement and empty-map clearing. Stable host facades
delegate these commands to the admitted current runtime; stale identity after switching rejects.

The final phase build passed 141 tests: 41 SDK, 33 Profile compiler/repository/preparation and
67 related desktop runtime/native/package tests. Desktop app compilation, Android app assembly
and platform instrumentation APK assembly passed against Cordis `f08918d`. New scenarios cover
wire null/default distinctions and descriptor fields, context layer provenance, grouped multi-instance
editing, configuration/context changes, group removal, unaffected providers, detached collections,
stale/no-op edits, invalid configuration, refused publication and successful retry. The real JAR
fixture verifies Profile DTO identity is shared with the host SDK while its implementation is private.

The final API 65 instrumentation APK was installed and all three `AndroidProfileHostTest` cases
passed on the ARM64 API 36 physical device (`OK (3 tests)`, 191.826 seconds). This revalidates
actual APK-provider switching, scoped data/App/Ubuntu workspace IO, stale references, restart,
failed allocation recovery and pre-authorization ADB rejection against the new SDK. The new
active-edit commands are behavior-tested on Desktop/common runtime and compiled on Android;
these device cases do not independently exercise the SDK editing command or prove privileged
authorization. All 141 desktop tests reported zero failures/errors and zero skipped cases.

This phase exposes active declaration editing, not complete Profile catalogue/draft/history
management. Public preview and historical activation, external Bundle import, credential-safe
export, typed host-module replacement and management/recovery UI still require implementation
and their own validation. Final whole-suite and platform evidence remain necessary for completion.

## Draft, preview and historical activation evidence

Plugin API 66 adds catalogue/draft reads, revision-checked draft creation/update, cloning,
deletion, preview, history and explicit committed/draft/historical activation. New shared DTOs
remain under the existing exported SDK namespace; external packages must rebuild against the
generated API 66 ABI. Preview reuses native preparation without allocating providers or
publishing metadata. Its package verification flag does not prove runtime configuration
validation, provider allocation or service readiness. Clones retain frozen bundle/code intent
while defaulting to independent business data scopes. Applying a historical recipe appends a
new generation rather than rewinding the committed head.

Drafts now use immutable documents and atomic authority pointers. Format 1 authorities and
legacy profile.json drafts remain readable; successful mutation publishes format 2 authority.
Tests cover stale writes, failed pointer publication after staging, readonly legacy migration,
frozen clone intent, structural preview diagnostics and historical append semantics. Native
host coverage checks preview does not allocate, active deletion is refused, failed draft
activation restores the committed runtime and deletion preserves business data.

The phase build passed 147 tests: 42 SDK, 37 Profile compiler/repository/preparation and 68
related desktop runtime/native/package tests, with zero failures/errors/skipped cases. Desktop
app compilation, Android app assembly and platform instrumentation APK assembly passed against
Cordis f08918d. The final API 66 instrumentation APK ran all four AndroidProfileHostTest cases
on the ARM64 API 36 physical device: OK (4 tests), 269.597 seconds. The added case exercises
SDK clone/preview, draft activation and historical restoration using actual APK providers and
preserved workspace files. Existing cases continue to cover scoped settings/history/workspace,
actual Ubuntu workspace execution, stale references, selection restart, failed allocation
reconstruction and rejection of scoped ADB before authorization.

This is focused phase evidence. Public management UI, independent recovery UI, verified Bundle
import, credential-safe portable export and full typed host-module replacement remain pending.
It does not establish full device-suite coverage, real Root/Shizuku authorization, sudden
power-loss durability or completion of the overall Profile goal.

## Host recovery commands and retained owner cleanup evidence

The stable native manager now admits host-owned metadata commands in RecoveryRequired even
when no product runtime remains. Catalogue activeProfileId is null in that state; durable
selection and history do not change merely because restoration failed. Explicit SDK activation
uses the same prepared generation publisher to recover committed, draft or historical intent.
The host's recoverTo(id) command supports normal recipe selection independently of the product
tree. These changes use existing API 66 contracts and do not expand the exported plugin ABI.

Old, candidate and restoration runtimes whose closure fails are retained for cleanup retry.
Unresolved closure prevents automatic restoration and new candidate allocation. Recovery first
prepares the target, then retries retained cleanup before allocating; continued failure leaves
RecoveryRequired. Failure or cancellation closes any returned candidate, retains unpublished
authority, and permits a later retry. Cancellation after durable publication retains the new
runtime. Normal agent/composition calls remain closed until successful recovery.

The phase passed 35 focused desktop tests: 14 KcodePluginRuntimeTest, 16 ProfileHostTest and
5 NativeProfileHostTest, with zero failures/errors/skipped cases. Six added cases cover failed
recovery and successful retry, old-owner retirement failure, cancelled allocation, failed candidate
retirement blocking automatic restoration, cancellation at recovery publication and SDK metadata/
draft/preview/activation without a live product runtime. Desktop app compilation and Android app
assembly also passed against Cordis f08918d. The prior phase's four Android device tests are not
new evidence for this recovery path; no device recovery-after-restoration-failure result is claimed.

Independent recovery presentation, initial construction failure recovery, plugin-facing command
submission ownership, full typed module replacement and Bundle import/export remain outstanding.
This phase does not mark the overall Profile goal complete.

## Typed alternate-module runtime transaction evidence

KcodePluginRuntime.replacePlugin(packageId, replacement) supports declarative in-process
alternates. It records a distinct stable module ID for every instance referencing the old
module, without changing instance IDs, configuration, groups, context or enable state. It
uses ordinary Replace operations and the existing candidate composition/tree/application/
generation transaction. Candidate module availability is published only after generation
publication, and failed validation/allocation/publication restores prior bindings and intent.
Cancellation withdraws suspended candidate allocation before restoring the old instance.

Selected code is a declaration reference, not a serialized Kotlin object. Restart without the
selected module rejects before product allocation; supplying the alternate replays retained
configuration and scopes. Same-ID typed overwrite rejects because it cannot record distinct
code selection. Legacy non-Profile replacement retains its existing entry point and behavior.
Borrowed in-process code remains host-authorized code, without claiming archive digest or ABI
verification. The shared Plugin API remains 66; these runtime commands are implementation APIs.

The final phase passed 77 focused desktop tests across runtime, Profile host/compiler integration
and real package transactions, with zero failures/errors/skipped cases. Three added tests cover
grouped multi-instance replacement, independent scopes, disabled/default configuration, retained
unrelated providers, missing selected code on restart, candidate identity conflicts, validation/
allocation/publication rollback and cancellation. Desktop app compilation and Android app
assembly passed against Cordis f08918d. No Android typed-alternate device execution is claimed.

Native factories still require explicit alternate-module catalogue factories and stable host
command exposure to retain these choices across native switching/restart. This runtime phase
does not supply that integration, public management/recovery UI, Bundle import/export or the
overall goal's final validation.

## Native alternate-module catalogue and stable selection evidence

Desktop and Android host/runtime factories now accept a detached moduleFactories map. Each
product allocation produces fresh lazy module definitions, validates keys against descriptor IDs
and rejects collisions with default code, native releases and infrastructure. Alternates are
available code only: shipped Bundle definitions exclude those IDs and startup does not create
implicit alternate instances. Factory functions must keep resource allocation inside provider
apply. Host-supplied code remains borrowed and does not claim verified archive identity.

KcodeProfileHost.selectProfileModule(packageId, moduleId, expected) admits selection through
the current runtime and checks active Profile identity/generation while holding its mutation
boundary. Selection uses the existing typed tree/generation transaction. Native switching,
restoration and restart reconstruct definitions through the same factory catalogue instead of
retaining old runtime mounts. The catalogue must be supplied again on restart; absent selected
code rejects preparation before provider allocation. Runtime and native factory implementation
APIs changed; the shared Plugin API remains 66.

The final phase build passed 79 focused desktop runtime/native/Profile/package tests, with zero
failures/errors/skipped cases. Two added native tests verify factory-map detachment, no implicit
instances, multiple configured instance selection, stale generation rejection, scoped clone/
switching, persisted selection restart, missing selected code, factory ID/default collisions and
failed selection rollback. Desktop app compilation, Android app assembly and the final Android
instrumentation APK assembly passed against Cordis f08918d.

The final instrumentation APK was installed on the ARM64 API 36 physical device and the added
typedAlternateCatalogueRetainsSelectionAndApkDataAcrossSwitchingAndRestart case passed:
OK (1 test), 88.686 seconds. It verifies no implicit alternate instance, stable selection,
stale generation refusal, actual APK filesystem implementation identity, App shell workspace IO,
scoped history isolation, stale filesystem rejection, missing-code preparation refusal and
persisted selection/data restoration with a freshly supplied factory catalogue. Only this new
case ran in this phase; the four earlier device cases retain their separately recorded evidence.
This does not establish privileged Root/Shizuku execution, device recovery after failed
restoration, full instrumentation-suite coverage or sudden power-loss durability.

Plugin-facing catalogue/command services, management and independent recovery UI, initial startup
failure recovery, verified Bundle import and credential-safe export remain outstanding. Focused
phase checks do not replace the final requirement audit and platform validation.

## Host-owned injected management commands (Plugin API 67)

Native Desktop/Android hosts now allocate a ProfileCommandGateway independently of the
replaceable product tree. Every allocation mounts a fresh infrastructure bridge exporting
KcodeProfiles. The neutral SDK exposes metadata/module queries, management state, a bounded
command queue and observable handles for activation, active edits and typed module selection.
Submit detaches request data and accepts synchronously; accepted work survives withdrawal of
the submitting bridge, and cancelling an awaiting observer does not cancel the command.
Withdrawn clients reject new calls. Explicit cancellation after durable publication returns
the command's own committed result, recorded only after product/application publication.
Closing the host cancels and joins running/queued work before releasing runtime resources.
RecoveryRequired retains the host client for metadata and explicit recovery activation.
Starting clients reject requests until host binding; consumers must not block plugin apply.

The final concentrated build passed 164 tests: 42 SDK, 37 Profile and 85 focused Desktop
runtime/native/Profile/package cases, with zero failures/errors/skipped cases. Desktop app
compilation, Android app assembly and instrumentation APK assembly passed against Cordis
f08918d. Six new gateway tests cover accepted activation across observer cancellation and
bridge withdrawal, detached serialized edits, cancelled queued work, queue saturation, host
closure, restoration failure/recovery, and cancellation at activation/edit publication.
The two publication tests also call the active agent to verify the committed implementation.
Those strengthened six tests and the updated instrumentation APK then passed a focused check.
Actual JAR loading checks shared identity for KcodeProfiles and ProfileCommandHandle.

The updated SDK 67 instrumentation APK was installed on the physical ARM64 API 36 device.
injectedManagementCommandsSurviveWithdrawalAndShareSdkIdentityWithApkProviders passed:
OK (1 test), 35.957 seconds. It exercises an injected client, cloning/draft activation,
cancelling an awaiting observer, withdrawal/replacement of the submitting client, module
catalogue queries, actual APK SDK identity and filesystem/App-shell scoped workspace IO.
Only the new case ran in this phase; prior device cases retain their separate evidence.
This does not establish Root/Shizuku authorization, startup failure recovery, device recovery
after failed restoration, full instrumentation-suite coverage or sudden power-loss durability.

Management/recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export, ordering/move operations and the final requirement/platform audit
remain outstanding. The implementation goal remains active.

## Positioned insertion and tree movement (Plugin API 68)

ProfileOperation.Insert now accepts an optional position, and ProfileOperation.Move records
a stable instance ID, destination group (null selects the root) and optional position.
Positions are evaluated after removal; null appends, negative values count from the end and
out-of-range values are clamped. Legacy insert documents continue to append. The existing
Profile/machine/launch compiler delegates both operations to the shared Cordis composer,
including source diagnostics and field provenance. Unknown parents, non-group destinations
and ancestry cycles reject preparation without modifying committed intent.

Cordis commit 9f1ceac supplies the generic composition and lifecycle changes. Cross-parent
movement withdraws the old branch before destination allocation and recreates its context;
same-parent ordering retains Fibers and resources. Cancellation cleans up loading descendants
before provider withdrawal, including nested moving groups. Failed publication restores the
previous hierarchy and bindings. Its complete core/loader/include/hmr JVM validation passed
132 tests (60/40/21/11), with zero failures, errors or skipped cases.

The concentrated kcode build passed 170 tests: 43 SDK, 39 Profile and 88 focused Desktop
runtime/Profile/package tests, with zero failures, errors or skipped cases. Desktop app
compilation, Android app assembly and instrumentation APK assembly passed against 9f1ceac.
New runtime tests cover context rebinding, committed-intent restart, invalid ancestry,
failed publication rollback and withdrawal/recovery through disabled groups. The actual JAR
fixture verifies moved instances remain active and share the new Move SDK identity.

The SDK 68 instrumentation APK was installed on the physical ARM64 API 36 device. The extended
injectedManagementCommandsSurviveWithdrawalAndShareSdkIdentityWithApkProviders case passed:
OK (1 test), 40.997 seconds, INSTRUMENTATION_CODE -1. It moves the actual APK filesystem provider
into a newly inserted group through the injected host-owned command queue, verifies generation
publication, stale filesystem rejection, stable instance identity, shared Move SDK identity,
and workspace access through the replacement filesystem and App shell. Only this extended
case ran in this phase; previous device evidence remains separate. This does not establish
privileged Root/Shizuku authorization, device recovery after failed restoration, full
instrumentation coverage or sudden power-loss durability.

Management/recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export, explicit native Bundle catalogues and the final requirement/platform
audit remain outstanding. Ordering and movement are now implemented; the overall goal remains active.

## Explicit shipped Bundle membership

The native distribution now declares the module membership of kcode.base, kcode.agent and
kcode.default-ui explicitly. NativeProfileBundles no longer derives membership from core.*
or provider.ui.* prefixes, and unknown default modules fail preparation with their identities.
This guards distribution changes; it does not restrict the extensible module catalogue or
explicit Profile selections. Alternate factories remain available without implicit instances.
Platform-unavailable members are omitted, and offered catalogue order is preserved within each
layer. Existing Bundle IDs, version 1 membership and frozen committed snapshots remain intact.
No SDK ABI change is required in this phase.

The concentrated validation passed 37 Desktop tests: 3 new Bundle declaration tests, 7 native
Profile host cases, 12 runtime Profile cases and 15 actual package integration cases. All were
executed with zero failures, errors or skipped cases. These cover explicit subset composition,
default UI layer omission, undeclared and invalid identities, empty templates, shipped native
startup/switching/restart, and actual JAR loading. Desktop app compilation and Android app
assembly also passed against Cordis 9f1ceac. This phase did not run Android instrumentation;
earlier physical-device evidence remains recorded separately.

Management/recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export and the final requirement/platform audit remain outstanding. Explicit
native Bundle membership is now implemented; the overall goal remains active.

## Initial default management surface

The new optional ui-profiles package exports DefaultProfileUiPlugin as the private native
provider.ui.settings.profiles release. A child waits for KcodeProfiles/KcodeUiSlots and
registers one settings section. Its session owns metadata queries and command observers;
withdrawal cancels and joins those calls without cancelling accepted host work. No SDK ABI
change is required. Fresh templates use kcode.default-ui version 2; frozen older generations
retain their previous Bundle content rather than gaining a new instance implicitly.

The initial screen supports catalogue/source selection, creation, cloning, JSON definition
editing, revision-checked draft saving, explicit discard, verified preview, effective trees,
diagnostics, module catalogue, history activation, confirmed deletion and host command state.
Refresh retains unsaved documents and their original authority revision. Confirmation binds
deletion to the selected target/revision. English/Chinese resource defaults follow app language
and allow translation catalogue overrides; spacing/colors use shared design tokens.

The concentrated integration build passed 38 Desktop tests: 3 Bundle, 7 native host, 12 Profile
runtime, 15 package integration and 1 new actual private-JAR UI contribution test. Six initial
session tests passed as part of that build. The first attempt exposed two static fallback tests
missing the new compile-only implementation from their test classpath; the test-only dependency
was corrected without adding that implementation to the production host classpath.

After strengthening deletion confirmation and reading the durable host command stream, the
final focused build passed all 7 session tests and the actual-JAR contribution test, with zero
failures, errors or skipped cases. Desktop compilation and Android app assembly passed again.
The session tests cover stale revisions/retained edits, invalid documents/identity changes,
save-before-preview, explicit discard/history targets, isolated creation/cloning, deletion
selection changes, command-observer withdrawal, pending-query cleanup and unverified activation
refusal. The actual native test covers private package registration, SDK-backed clone/preview,
version 2 selection, withdrawal and fresh contribution recovery.

No desktop/device rendering or Android instrumentation ran for this UI phase. These checks
establish session behavior, registration and build compatibility, not visual acceptance or
actual APK UI resource rendering. Visual tree forms, navigation-away confirmation, rendered
acceptance, independent recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export and the final requirement/platform audit remain outstanding.

## Structured tree and Bundle draft editing

The ui-profiles settings section now includes forms for plugin/group insertion, parent/position
movement, module replacement, configuration codecs, enable/disable, removal and service scope
editing. Bundle references can be added, removed or reordered, and the name field can rename
the selected Profile. Form values become ordinary SDK operations; the shared compiler remains
the authority for actual composition. Form validation checks identities, available code,
positions, scalar codecs and parent cycles without provider allocation.

Each structured action binds the selected source and authority revision, saves a draft and
reloads the host preview. It cannot overwrite an unsaved raw document or a newer editor state.
Historical intent must be cloned before structured editing. Successful draft publication is
recorded before preview queries, so a query failure does not hide the saved document. Service
scope fields initialize from the effective entry, and field-source inspection uses its preview
origins. Late asynchronous callbacks quietly withdraw through PluginOperationOwner.runIfOpen;
retained synchronous edit/activation references still reject stale calls.

The concentrated validation passed 17 tests: 10 UI session, 6 tree form/compiler and 1 actual
native private-JAR contribution case, with zero failures, errors or skipped cases. New cases
cover nested insertion/reparenting/root return, default versus explicit Unit configuration,
configuration/identity/module/position refusal, replacement identity guards, local/shared
service realms, enable/disable/removal, stale/dirty structured forms, successful save followed
by failed preview, ordered Bundle references and historical editing refusal. The native case
now saves a grouped filesystem move and disable/enable operations in a cloned draft, verifies
updated package-verified previews and durable draft operations, and proves the active Profile
and generation stay unchanged. Desktop compilation and Android app assembly passed against
Cordis 9f1ceac. No public SDK ABI change is required.

This phase did not run desktop/device rendering or Android instrumentation. Visual acceptance,
navigation-away confirmation, independent recovery UI, initial startup failure recovery,
verified Bundle import, credential-safe export and the final requirement/platform audit remain
outstanding. Structured forms are implemented; the overall goal remains active.
