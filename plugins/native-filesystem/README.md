# Native filesystem implementations

This module owns native filesystem/workspace policies and IO. Android accepts real absolute
paths permitted by the OS and maps /workspace to the app/Web workspace. Desktop constrains
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
graph. Real JAR tests package the native-filesystem, capability-providers and skills
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
and a host-input lease. `packagedDesktopJar` includes the private `capability-providers`
adapter with deterministic entry order and duplicate rejection. The Android distribution
includes that same private project explicitly, with shared SDK/framework code excluded.
The host no longer links this module or its `capability-providers` implementation adapter
into its production classpath; legacy adapter fixtures remain test dependencies. Default composition
profiles retain filesystem as a native feature package, even when other defaults are disabled.
