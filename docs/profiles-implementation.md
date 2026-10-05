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
mutations currently reject declarative mode pending Profile transaction routing.

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
