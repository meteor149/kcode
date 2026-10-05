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
