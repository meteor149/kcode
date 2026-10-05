# Cross-platform plugin packages

Cordis Kotlin's optional `packages` module owns the generic manifest, target selection,
exact dependency resolution, and offline archive tooling. kcode's `plugins/package-provider`
owns SDK compatibility, configuration, native checks and the `pluginPackages` service.
`AgentPluginManager` owns transactional composition and persistence. Application policy
does not become part of Cordis core.

Archive import is implemented for desktop JVM and Android. Native builds embed a trusted
catalog and 86 real packages under `kcode/plugins`: Message Codec, Tools,
System Prompt, Continuations, Model Settings, Goal, Schedule, Subagent, Settings Commands,
Application, pages, settings, Markdown, Goal UI, Localization, Sessions, Conversation Execution,
Agent Loop, Native Filesystem, Skills, Notifications, Schedule Dispatch, Conversation Export and filesystem/skill/artifact/Web Search/Shell tool consumers and native tool approvals and the LLM service registry, model adapters and the complete Web Search feature and settings-driven interaction, settings storage, history storage and Artifact storage  and Web container providers/tools and the overlay registry/Android overlay provider and native Shell/Ubuntu providers and settings mode policy. Both factories stage and load them at startup. Their
implementations are excluded from host runtime dependencies. Native system Shell
and Ubuntu entries execute from their verified independent artifacts.
Desktop supports 83 of these packages; Android supports 88. On ARM64 Android all 88 are
selectable; other supported Android architectures select 87, with the Ubuntu provider absent
and its declared consumers Pending. Notification permission UI and generation foreground
execution are Android-only packages. Desktop Shell tools are
desktop-only; Android Shell and Ubuntu tools are Android-only. Trusted catalog records copy the actual manifest `variants`, including runtime, system,
architecture family/bitness, version ranges, distribution and feature requirements. Host-aware staging validates the complete
catalog and skips incompatible payloads before reading them. Import still checks the actual manifest independently.
Archive import separately validates each manifest and its variants; catalog labels do not
bypass SDK or payload validation.
Production hosts retain SDK contracts, composition/installation infrastructure and SDK-only
input adapters. Supported default product implementations execute from the catalog archives.

`provider.shell.platform` contains desktop and Android variants. Desktop supplies its
existing absolute workspace String; Android uses the Unit-configured `AndroidPackagedShellPlugin`
entry and its bytecode-only APK. Both factories load the verified variant, including profiles
with default composition disabled. Android injects API 45 `KcodeShellMode`: the independently
packaged `policy.shell-mode.platform` reads settings, or an SDK-only host adapter borrows the
caller callback. Missing policy suspends the executor and withdrawing it cancels active work.
The Android-only `provider.shell.ubuntu` uses the same policy and declares ARM with 64-bit support. Unsupported
hosts skip this package; its service-dependent tool consumer remains Pending. Shell tool
consumer packages retain their existing platform support.

Android preparation releases now separate system Shell (ARM/x86, 32/64 bits, no assets/JNI) from
Ubuntu (ARM, 64 bits, its verified PRoot libraries/rootfs). Build them with
`:distribution:packager:packageNativeSystemShell` and `packageNativeUbuntu`. Their outputs
are under `distribution/packager/build/preparation`. The trusted catalog also contains the
dual-target system Shell release and Android-only ARM64 Ubuntu release.
The device Shell fixture imports both through normal package transactions.
See [native execution](../plugins/native-execution/README.md) for entry points and limitations.

## Harness reference

The reference is DeepSeek Harness commit `47f943859bef60e4160492346772ded9b24f765a`:
`packages/llm/llm-deepseek/package.json` declares identity, exports, files and private/peer
dependencies; `packages/bundle/base/package.json` exposes `dsh.bundle.patch`, and
`cordis.patch.yml` composes stable mount rows. Harness uses npm, not this native ZIP format.
See [the Harness reference](deepseek-harness-plugin-spec.md).

Packages describe executable variants, host extensions describe kcode requirements, and
bundles/profiles choose providers and configuration. Package presence does not satisfy
`inject`: required service availability still controls activation.

The `feature.web-search` release contains desktop CIO and Android OkHttp variants.
Transport implementations and their Ktor/SLF4J dependencies remain private to the selected
artifact; Android also carries private OkHttp. Shared SDK Kotlin, coroutines, serialization,
IO and Okio types are excluded. Both native factories stage this release in place of their
former static HTTP mount, including the existing platform features for custom profiles.
Configuration policy, settings commands and the settings form are owned children of this
same release. See [search feature packaging](../plugins/web-search/README.md).

`provider.web-containers.platform` and `consumer.tools.web-container` are separate dual-target
releases. The native provider selects the desktop workspace-configured entry or Android
Unit-configured entry, and owns browser/window resources. Android WebKit remains private
to the APK; Core/Parcelable identities are SDK peers of the native host window/FileProvider
adapters. The product resource table is read from the verified deployment.
Caller-supplied controllers use an SDK-only composition binding with the existing session
cleanup semantics. See [Web container packaging](../plugins/web-container/README.md).

## Archive and manifest

One `.kplugin` is an immutable ZIP release of one logical plugin. Root `plugin.json` declares per-system targets and
execution-neutral runtime/artifact variants. The current kcode loaders accept desktop JARs
and Android APKs; other runtime kinds require an actual host loader. A host mounts exactly one variant. Shared Kotlin
source compiles separately to JVM classes and DEX/resources. kcode does not currently support iOS/browser
execution. Every payload file, including optional README/LICENSE, is listed with size/hash.
The external raw archive SHA-256 covers both the manifest and all payloads.

```text
provider.message-codec.envelope-1.0.0.kplugin
├── plugin.json
└── targets/
    ├── desktop/plugin.jar
    └── android/plugin.apk
```

This example uses illustrative hashes/sizes; ABI values come from the actual matching SDK:

```json
{
  "formatVersion": 1,
  "id": "ai.example.search",
  "version": "1.2.0",
  "dependencies": [],
  "variants": [
    {
      "id": "desktop",
      "targets": [
        {"system": "windows", "arch": ["x86", "arm"], "bits": [64], "minSystemVersion": "10.0.22000"},
        {"system": "macos", "arch": ["arm"], "bits": [64], "minSystemVersion": "14.0", "maxSystemVersion": "15.7"},
        {"system": "linux", "arch": ["x86"], "bits": [64], "minSystemVersion": "6.5"}
      ],
      "artifact": "targets/desktop/plugin.jar",
      "runtime": {"id": "jvm", "entryPoint": "ai.example.SearchPlugin", "minVersion": "17"},
      "extensions": {"ai.meteor.kcode": {"pluginApi": 45, "runtimeAbi": "1111111111111111111111111111111111111111111111111111111111111111", "capabilities": ["web-search"]}}
    },
    {
      "id": "android",
      "targets": [{"system": "android", "arch": ["arm", "x86"], "bits": [32, 64], "minSystemVersion": "15"}],
      "artifact": "targets/android/plugin.apk",
      "runtime": {"id": "android-dex", "entryPoint": "ai.example.AndroidSearchPlugin", "minVersion": "35", "metadata": {"packageName": "ai.example.search"}},
      "extensions": {"ai.meteor.kcode": {"pluginApi": 45, "runtimeAbi": "2222222222222222222222222222222222222222222222222222222222222222", "capabilities": ["web-search"]}}
    }
  ],
  "files": [
    {"path": "targets/desktop/plugin.jar", "size": 120000, "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
    {"path": "targets/android/plugin.apk", "size": 160000, "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
  ],
  "extensions": {"ai.meteor.kcode": {"configuration": {"kind": "unit"}}}
}
```

Omit unsupported systems. The generic Cordis schema does not tie operating systems to
JVM, DEX or any suffix: each variant declares its own runtime and opaque artifact path.
One artifact may cover multiple systems if its actual loader supports them. Native DLL/SO/
dylib/framework, WASM and script entries can be described without Java. kcode currently
implements `jvm` and `android-dex` loading; describing another runtime does not execute it.

| Target field | Rules |
| --- | --- |
| `system` | Exactly `windows`, `macos`, `linux`, `android` or `ios`. |
| `arch` | Nonempty unique subset of `arm`/`x86`; no `any` or ABI-specific labels. |
| `bits` | Nonempty unique subset of 32/64; omitted means both. Native artifacts must restrict this to their actual ABIs. |
| `minSystemVersion`, `maxSystemVersion` | Optional inclusive numeric bounds, with one to four components. Missing components are zero; a short maximum is an exact bound, not a wildcard. Unknown versions reject any bound; inverted ranges are invalid. |
| `distribution` | Linux only: exact `id` and optional inclusive `minVersion`/`maxVersion`. This constrains the distribution release independently of the kernel. |
| `requiredFeatures` | Optional set of open feature identities which the host must actually provide. A sufficient OS version does not establish availability or permission. |

System versions use Windows NT version/build/revision (`10.0.22000` for the initial Windows 11
build), macOS release, Linux **kernel**, Android release and iOS release. Linux distribution
requirements use the host's `os-release` ID/VERSION_ID; IDs are exact (`ubuntu` does not
mean every Debian derivative). Numeric comparison accepts release forms such as `22.04`.
Unknown distribution versions cannot satisfy bounds. Windows detection reads the build and UBR update revision when the JVM reports only
NT major/minor. Missing build/revision facts make the host version unknown. Preview/non-numeric Android versions
remain unknown.

`runtime` has an open `id`, an opaque `entryPoint`, optional numeric `minVersion` and
optional loader-specific `metadata`. kcode's JVM loader interprets the entry as a class and
the minimum as Java level. Its Android DEX loader uses API level and requires
`metadata.packageName`. These checks belong to kcode, rather than the generic Cordis model.
Host SDK ABI and runtime-loader versions are separate from system versions.

A native example (metadata/selection support; a native kcode loader is not yet implemented):

```json
{
  "id": "linux-native",
  "targets": [{
    "system": "linux", "arch": ["x86"], "bits": [64],
    "minSystemVersion": "6.5", "maxSystemVersion": "6.8.12",
    "distribution": {"id": "ubuntu", "minVersion": "22.04", "maxVersion": "24.04"},
    "requiredFeatures": ["linux.io-uring"]
  }],
  "runtime": {"id": "native-library", "entryPoint": "kcode_plugin_init", "minVersion": "1"},
  "artifact": "targets/linux/plugin.so"
}
```

Package identity, SemVer, exact dependencies, payload hashes and configuration are unchanged.
Variant IDs are unique. Variants using the same runtime must have disjoint selectors;
different distribution IDs or disjoint inclusive version ranges can distinguish them.
Minimum-only bounds and positive feature requirements alone cannot eliminate overlap.
Selection requires exactly one compatible variant, with no row-order preference or load-error
fallback. Every artifact is listed in `files`; the manifest declares `formatVersion: 1`. JSON rejects unknown fields, duplicate keys and malformed UTF-8.

## SDK requirements and configuration

Other Cordis applications do not need kcode extensions. The package kcode extension contains
exactly `configuration`; the variant extension contains `pluginApi`, `runtimeAbi` and
`capabilities`. Configuration uses `StoredPluginConfiguration`: Unit/null, JSON, string,
boolean, int/long, or finite float/double. No arbitrary Kotlin objects or plaintext credentials.
Entry adapters decode the portable representation and use `ConfigValidator` to validate it.
Capabilities are diagnostic labels, not permissions or proof of services.

`pluginApi` exactly matches `CurrentPluginApiVersion`, currently 54. Generated ABI descriptors
hash actual shared compile artifact contents, exported SDK/framework prefixes, compiler
versions and entry convention. Contents are sorted independently of ZIP timestamps. SHA-256
of `sdk-abi-<platform>.txt` is `runtimeAbi`; authors use the matching host descriptor. Matching
fingerprints remain a prerequisite, not proof of binary or behavioral compatibility.

SDK/framework dependencies are compile-only in plugins. Import rejects duplicate shared
JVM classes/DEX defined types, missing entries, incompatible JVM bytecode, native ABI and
APK package/minSdk mismatches. Android DEX containers are made read-only before loading and
checked again on restart. Resources belong to the verified artifact.

Package dependencies ensure availability, not private class linkage or services. Imported
specs leave Loader `dependencies` empty; direct-spec installs retain existing artifact-graph
semantics. New cross-plugin binary contracts require reviewed SDK exports/API changes.

## Build and import

Build the independent Message Codec JAR/APK archive on Windows:

```powershell
.\gradlew.bat :distribution:packager:packageMessageCodec
```

Output: `distribution/packager/build/packages/provider.message-codec.envelope-1.0.0.kplugin`
with an external `.sha256` sidecar. Its APK compiles common source against the SDK without
bundling SDK/Kotlin classes; it is not a host APK or wrapped AAR.

`:distribution:packager:packagePlugin` takes `-PpackageId`, `-PpackageVersion`, `-PpackageEntry`,
`-PpackageOutput`, optional `-PdesktopArtifact`/`-PdesktopSdkAbi`, and optional
`-PandroidArtifact`/`-PandroidPackageName`/`-PandroidSdkAbi`. Optional `-PpackageConfiguration`
points to a portable configuration JSON file. This convenience exporter defaults to portable
variants. APKs containing native libraries require explicit `packageTargets` with matching
family/bitness coverage; declarations cannot claim native ABIs absent from the APK.
`-PpackageCapabilities=capabilityA,capabilityB` sets their advertised capabilities.
`-PandroidEntry` overrides the Android entry class when it differs from the desktop entry.
The bundled filesystem release uses this option and includes its private provider adapter
in each artifact. Native hosts supply the desktop workspace String on initial installation;
Android uses Unit configuration and a native host-input lease. Saved configuration takes
precedence over bundled defaults during upgrades.
Pass `-PpackageTargets=<targets.json>` to the convenience exporter to declare exact system,
family, bitness, system-version ranges, distribution and feature targets. The file contains a JSON array in the target
format above. Default desktop targets cover Windows/macOS/Linux; default Android targets
require release 15 and API 35. Native ABI coverage is checked. Use the generic Cordis packer for other loaders, iOS
artifacts or custom entries/dependencies.
`:distribution:packager:packageNativeUbuntu` builds a preparation release at
`distribution/packager/build/preparation/provider.shell.ubuntu-1.0.0.kplugin` with an
Android ARM64 variant. The same provider also ships in the trusted default catalog;
host-aware staging skips it on incompatible Android architectures.
SDK descriptors are under `plugins/package-provider/build/generated/packageAbi/<platform>/`.
`-PcordisSource=<checkout>` optionally substitutes a local Cordis composite build for development.

Both native hosts expose import through the manager:

```kotlin
runtime.pluginManager.importPackages(listOf(
    PluginPackageImport(archivePath = localArchive, sha256 = trustedExpectedDigest),
))
```

Supply the dependency set or have exact releases installed. Import overrides can explicitly
set configuration/enable state; updates otherwise preserve them and first installs use defaults.
Cross-device profile transfer re-resolves variants instead of copying absolute artifact paths.

## Transactions, trust and lifecycle

Inspect every archive and all payloads, including unselected variants, before tree mutation.
Resolve exact dependencies and reject incompatible updates without changing the committed
release. Snapshot inputs to private staging, verify/extract and atomically publish digest-named
generations under `cordis_plugins/packages`.

Finish/cancel active agent turns before composition changes. Archive preparation is cancellable
and does not modify the tree. Apply batches through `AgentPluginManager`, restore the previous
composition on failure, then save native specs and package locks in one atomic
`PluginCompositionSnapshot` before publishing the runtime view. Restoration rechecks archive,
payload, variant and SDK. The snapshot declares `formatVersion: 1`.

Disabling a package with enabled package dependants requires the same `applyChanges` batch to
disable them. Uninstallation requires removing installed dependants in that plan. Service-only
consumers may become Pending. Replacements use the exact built-in logical ID; uninstalling a
replacement does not reactivate the built-in. Stale resolver handles reject work.

Expected digests come from a trusted release channel or explicit local import policy. Hashes
authenticate bytes, not publishers; APK signing does not cover outer metadata. Imported APKs
are artifacts, not separately installed apps; their manifests cannot add host permissions/components.

The archive layer rejects traversal, absolute/drive/UNC paths, backslashes, Windows device
names, duplicate/case-colliding names, ZIP symlinks, encryption, ZIP64 and nested package
archives. Host limits bound entry counts, compressed/expanded bytes and expansion ratios;
packages cannot raise them. Loose private classpaths and JVM JNI files need future extensions.
Generations remain for rollback/recovery; automatic garbage collection is not implemented.
Durable provider data belongs outside package directories.

## Remaining default-distribution migration

`KcodePluginRuntimeConfig.bundledPackages` supplies the trusted native distribution profile.
Startup resolves the complete offered set before restoration and commits only after successful
mounting. Snapshot `bundledPackages` records the last offered archive hash. A matching installed
release can upgrade while retaining config and enable state; a different installed release is
a user replacement. A tracked ID with no installation is an explicit uninstall and is not
reinstalled on later boots/upgrades. Legacy builtin enable state transfers to the package.
An identity/hash mismatch, changed bytes under the same release version, invalid dependency
graph or failed publication leaves the prior snapshot intact.
Loading a snapshot checks its structure before reconciling the trusted bundled releases.
Historical API descriptors belonging to those releases may be replaced with verified current
packages before activation. Unreplaced incompatible user packages still fail startup; their
API numbers are never rewritten to claim compatibility.

`stageBundledPlugins` embeds `index.json` and archives in Android assets and desktop resources.
The SDK-only provider exports reuse compiled Android library AARs through isolated application
targets declared in `settings.gradle.kts`. Their dependencies are non-transitive: the SDK is
compile-only and is not copied into the plugin. Providers needing private libraries require
an explicit packaging dependency plan before joining this path.
The native package staging helper reads only this trusted embedded catalog. Message Codec's
manifest version includes a content hash of artifacts, SDK descriptors and configuration, so
changed builds do not republish the same immutable release. The stable output filename is not
the authoritative release version; `plugin.json` is.

The default product uses the catalog's independent provider/consumer releases and the existing
startup/upgrade transaction. Additional providers must preserve their configuration, SDK
identity and resource ownership when joining this path. Production-classpath and APK DEX
checks cover all 83 desktop and 88 Android entry classes; see the current acceptance record
in [verification](verification.md).

See [plugin development](plugin-development.md), [architecture](plugin-architecture.md) and
[verification](verification.md) for current contracts and evidence limits.

The SDK declares vendor Koog client artifacts explicitly instead of obtaining them through
a statically linked model-adapter feature. Desktop exports Smithy runtime identities used
by Bedrock's public client constructors. Private adapter code implements those shared
interfaces; duplicating Smithy classes inside a plugin would break the constructor boundary.
The corresponding exported-class and compiler fingerprints are part of Plugin API 45.

Model adapters ship as eleven logical releases. Ten have desktop and Android variants;
Bedrock has only a desktop variant. Unsupported Bedrock catalog payloads are skipped on
Android before resource reads, and the default composition supplies no Android Bedrock
mount. Koog clients remain explicit framework peers in the SDK; provider implementations,
catalogs and generated localized labels remain private to the selected packages.

The settings-driven `provider.interaction.platform` entry ships from `interaction-settings`
with desktop and Android variants and Unit configuration. It consumes the SDK settings and
approval services, reads committed permission mode on each call, and owns its callbacks.
Desktop defaults and settings-driven Android defaults use this package. Explicit Android
mode/approval callbacks use SDK-only host input adapters and do not stage this
settings-driven package. Production hosts exclude the legacy `interaction` implementation
module; adapters borrow inputs and cancel/join their admitted calls on withdrawal.
Custom overrides and headless profile selection remain authoritative.

The settings storage package selects `DesktopNativeSettingsPlugin` with an absolute data-file
path and `AndroidNativeSettingsPlugin` with Unit configuration. Desktop DataStore and Android
MMKV remain SDK peers, while codecs, defaults and encrypted-key bootstrap policy stay private.
Native factories preserve existing data paths/IDs and do not allocate stores. Explicit caller
stores/factories use a neutral composition binding and do not stage the default settings
package. Store revocation must reject retained access and preserve durable data on remount.
