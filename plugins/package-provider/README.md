# Native plugin package provider

`cachedRelease(sha256)` locates a locked native archive by its validated digest. It does
not verify or execute the file; ordinary resolver verification remains mandatory. First
imported Profile drafts use this locator to preserve frozen code selection independently
of source paths or newer bundled offers. See [Bundle archives](../../docs/profile-bundle-archives.md).

`NativePluginPackageResolver.profileExportSchema(spec)` returns the selected release's
`ai.meteor.kcode.profile-export` manifest data only after deployment, artifact, SDK and
descriptor verification. It uses the immutable generation in the stored specification;
there is no host-resource fallback, live feature callback or newer-release substitution.
The Profiles module interprets the schema dialect. Missing schema remains absent.

`NativePluginPackagesPlugin` supplies `KcodePluginPackages` (`pluginPackages`). Native hosts
mount it as `provider.plugin-packages.platform`. It depends on the neutral SDK and the
optional Cordis `packages` library, not any concrete product provider. Allocation happens
in `apply`; a `PluginOperationOwner` cancels/joins admitted calls and rejects stale handles.

Configuration is constructor-supplied app-private directory, `PackageHost`, matching SDK ABI,
and Android artifact metadata verifier. The Cordis config is Unit. Host factories pass the
actual system/version and desktop process/Java level or Android process ABI/API level. Android requires an APK
package/minSdk verifier; imports cannot use a host resource fallback.

The resolver checks trusted expected archive hashes, all variants/payloads, exact package
dependencies, kcode extensions and shared-class exclusions before returning native specs.
Archive preparation does not mutate the plugin tree. The manager commits the selected specs
and release locks as one persisted composition. Configuration and enable state survive update;
disabled required packages are not silently enabled. Resources and implementations remain
private, and package availability does not satisfy Cordis `inject`.

SDK ABI descriptors are generated from actual shared compile artifacts and export boundaries
by `generateDesktopPackageAbi` / `generateAndroidPackageAbi`. The compiler/entry descriptor
and sorted content hashes produce the platform ABI fingerprint; authors compile against the
same SDK and copy this identity. Matching API numbers alone are insufficient.

Each `ai.meteor.kcode` package variant records `pluginApi` (the API used to compile it) and
`pluginApiRange` (`minimum`/`maximum`, the host APIs the publisher supports), mirroring the
peer dependency ranges used by DeepSeek Harness packages. The host selects a variant only when
its current API is inside that range and the compiled API is in the host's supported range.
Current-API packages must match the host SDK ABI fingerprint. For older APIs, the fingerprint
remains bound to the package metadata and immutable profile lock; the declared range is the
publisher's binary-compatibility claim, so publishers must validate each API they include.
Legacy metadata without a range gets one-step forward compatibility for the immediately
previous supported API. New packages should always publish the explicit range.

Package presentation metadata belongs to Cordis: its root manifest fields include
`displayName`, `description`, `license`, `author`, `contributors`, `homepage`, `repository`,
`bugsUrl` and `keywords`. Author, contributor, homepage, repository, bug tracker and keyword
fields use Cordis package format 2. The
`ai.meteor.kcode` extension contains Kcode configuration only. Use
`kcodeConfigurationExtension(...)` to write it; Cordis validates package presentation fields
while decoding and validating the manifest. Full field rules and an example are in the
[package format guide](../../docs/plugin-package-format.md).

The package format selects Windows, macOS, Linux, Android and iOS independently, with ARM/x86 families,
32/64-bit constraints and optional numeric system-version ranges, distribution constraints and required features. kcode loads self-contained desktop JARs and Android APKs; iOS is metadata only.
Windows uses NT version/build, macOS its release, Linux its kernel version and Android its
release alongside the independent API-level constraint. Unknown versions reject bounded targets. Linux distribution ID/version comes from os-release; feature requirements need actual host facts.
Loose classpaths/JVM JNI files,
publisher signing, remote downloads, automatic generation garbage collection and a plugin
management UI are not implemented. Immutable generations remain available for rollback and
restart. Durable plugin data must not live in them. Message Codec, Tools, System Prompt,
Continuations, Model Settings, Goal, Schedule, Subagent, Settings Commands, Application, pages, settings, Markdown, Goal UI, Localization, Sessions, Conversation Execution, Agent Loop, Native Filesystem, Skills, Notifications, Schedule Dispatch, Conversation Export and filesystem/skill/Web Search/Shell tool consumers and native tool approvals and the LLM service registry, model adapters and search settings policy and settings-driven interaction, settings storage, history storage now ship as
independent bundled packages, including HTTP transport, overlays,
native Shell/Ubuntu providers and Android Shell mode policy. Production hosts retain only
SDK contracts, composition/installation infrastructure and SDK-only input adapters. The native
catalog staging helper verifies resources embedded by the distribution before offering them
to the runtime's bundled install/upgrade path.

Catalog staging validates every record, including unsupported targets and global identity
uniqueness, before creating a cache directory or opening any package resource. Target
selection then skips unsupported archives; the host-aware overload also checks optional
system, architecture family/bitness, version ranges, distribution, feature and runtime selectors before opening payloads. ARM64 Ubuntu is skipped on
other Android architectures. Staging requires complete host system/runtime facts; earlier platform-only selectors are removed. Verified digest-named copies are reused.
Failed copies leave the prior cache file untouched and remove temporary files. Transport
staging does not replace the resolver's subsequent archive/manifest/ABI verification.
`BundledPackageCatalogTest` covers late invalid metadata, cross-target duplicate IDs,
removed-schema rejection, unsupported platform/architecture resource exclusion, invalid architecture
selectors, system/distribution ranges, required features and runtime alternatives and cache repair after corruption.

See [package format and tooling](../../docs/plugin-package-format.md) for fields and commands.
Desktop integration tests exercise actual JARs, batch rollback, restart, withdrawal and
cancellation. `AndroidPluginPackageIntegrationTest` imports a genuinely independent APK from
the dual-target archive on device; it provides app-UID loading evidence, not privileged/root
or Shizuku evidence.
