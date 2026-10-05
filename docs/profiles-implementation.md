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
