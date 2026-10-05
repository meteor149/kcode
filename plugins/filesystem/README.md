# Filesystem tool consumer

`consumer.tools.filesystem` uses `KcodeTools` and `KcodeFileSystem` to register the
Koog read, list, write, edit and media-read tools. It does not allocate a filesystem
backend. Withdrawal removes the contribution; backend withdrawal leaves it Pending
until a replacement is available.

Native builds distribute this consumer as a dual-target `.kplugin`. The entry is
`ai.meteor.kcode.plugin.feature.FilesystemToolConsumerPlugin` with `Unit` configuration.
The implementation and its adapters remain private to the package. Native hosts load
the package even when `profile.includeDefaults` is false, preserving platform feature
composition; service availability still controls activation.

Production-classpath JAR and real APK distribution tests prove host class absence and
contribution removal/re-registration. The desktop fixture also withdraws/restores the
packaged filesystem provider and checks dependency recovery.

## Native filesystem implementations

This module owns native filesystem/workspace policies and IO. Android accepts real absolute
paths permitted by the OS and maps /workspace to the app workspace. Desktop constrains
file access to its workspace. Skill workspaces enforce normalized paths, containment,
canonicalization, sorted entries and their existing size limit. Platform factories supply
only the root path and compose providers; shared retains AgentWorkspace/Entry contracts.

`DesktopNativeFileSystemPlugin` (absolute workspace String) and
`AndroidNativeFileSystemPlugin` (Unit plus a host-input lease) create native adapters
inside each activation. They publish `KcodeFileSystem` and the API 16
`KcodeSkillWorkspace` contract. The scoped workspace rejects calls after retirement.
`WorkspaceSkillsPlugin` declares that workspace dependency and creates its own discovery
and materialization state; withdrawal makes it Pending and cancels its old operations.
Restoration creates new caches while retaining user files. Application hosts no longer
construct native filesystem/skill workspace objects or a skill runtime.

Private imported packages must include their capability-provider adapter implementation
graph. Real JAR tests package the filesystem, capability-providers and skills
implementations; actual APK tests load the same formal entries and verify revocation,
Pending/restoration, fresh builtin materialization and persistent user files.

The capability provider publishes a neutral FileSystemBackend, owns pending calls and
revokes stale references. Caller-owned IO streams retain their established lifecycle.
Native implementation classes remain private to the plugin loader; workspace DTOs,
Koog and kotlinx.io SDK types preserve host identity. API 8 rejects dependencies on former
shared native implementations. Android unit/media tests, desktop workspace behavior tests
and a true private APK file/workspace provider test verify the migration.

Native builds now distribute `provider.fs.platform` as a dual-target `.kplugin`. The desktop
variant uses `DesktopNativeFileSystemPlugin` with an absolute workspace String supplied by
the host on first install; Android uses `AndroidNativeFileSystemPlugin`, Unit configuration
and a host-input lease. Cordis `prepareProviderFsPlatformDesktopJar` includes the private `capability-providers`
adapter with deterministic entry order and duplicate class rejection. The Android distribution
includes that same private project explicitly, with shared SDK/framework code excluded.
The host no longer links this module or its `capability-providers` implementation adapter
into its production classpath; legacy adapter fixtures remain test dependencies. Default composition
profiles retain filesystem as a native feature package, even when other defaults are disabled.


Provider and tool consumer share this source module while retaining their existing
package IDs, entry points and independent enable states. Tool consumers inject SDK
services; disabling a tool consumer does not close the provider or its workspace.
