# Independent plugin distribution

`packager` is a JVM build tool using Cordis archive tooling and kcode SDK extensions.
`message-codec-android` builds an actual plugin APK from shared provider source with the SDK
as compile-only dependencies and no duplicate Kotlin runtime. The corresponding JVM artifact
comes from `plugins/message-codec:desktopJar`.

The shared `android-plugin` build script packages the compiled Android AARs for `tools`,
`system-prompt`, `continuations`, `model-settings`, `goal`, `schedule`, `subagents`,
`settings-commands`, `application`, `ui-pages`, `markdown`,
`ui-contributions`, `default-ui-bridge`, `localization`, `session-history`, `conversation-execution`, `agent-loop`,
`native-filesystem`, `skills`, `native-notifications`, `schedule-dispatch`, `conversation-export`, `filesystem`, `skill-tools`, `artifact-tools`, `web-search`, `shell`, `native-tool-approvals`, `llm-service`, `llm`, `interaction-settings`, `settings-repository`, `history-repository`, `artifact-repository`, `native-execution`, `web-container` and `conversation-overlay`,
with non-transitive implementation dependencies and compile-only neutral/default
UI SDKs and reusable UI primitives. `settings.gradle.kts` declares each distinct application
target and isolates its build directory. The generated default catalog is authoritative; the System
Prompt service and default contribution have separate entries and package lifecycles.
Goal ships as one `feature.goal` package containing its commands, sessions, tools, policies and
optional presentation. Platform compatibility is declared per archive. Notification permission
UI and generation foreground execution are Android-only. Shell tools have one desktop-only
package and two Android-only packages. The overlay registry supports both targets, while its
native system-window provider is Android-only. Catalog records declare Windows, macOS, Linux,
Android or iOS targets with architecture families, bitness, optional system-version ranges, Linux distribution ranges and required system features. Native staging skips
unsupported packages before opening their payload resources. Archive variant selection still
validates the actual manifest independently of the trusted catalog.

Run `:distribution:packager:packageMessageCodec` to produce the dual-target `.kplugin` and
external SHA-256 sidecar under `distribution/packager/build/packages`. The generic
`packagePlugin` task can package desktop-only, Android-only or dual-target portable releases;
see [the format guide](../docs/plugin-package-format.md) for its properties. Neither tool
executes entry classes while building an archive.

Native host resource tasks depend on `stageBundledPlugins`, which embeds the trusted catalog
and 84 independent archives under `kcode/plugins`. Host factories stage these resources and
the runtime installs/upgrades them before publishing the composition. Configuration, disabled
state, user replacements and explicit uninstalls survive restart. Generated default release
versions include a content hash covering the payload and SDK compatibility metadata.

These are build/distribution modules, not host implementations or a second installed Android
application. Supported default product implementations run from their offline archives; host
bootstrap, SDK contracts and input adapters remain in the host. Further exports must preserve
each provider's config, shared identity and independent resources.

Android native execution has two preparation artifacts. `native-execution-android` retains
the ARM64 PRoot libraries and Ubuntu image. `native-shell-android` uses the same compiled AAR
with assets/JNI removed, so ordinary system Shell remains bytecode-only. The packager's
`packageNativeUbuntu` and `packageNativeSystemShell` tasks produce Android-only preparation
archives targeting Android ARM 64-bit and both ARM/x86 families at 32/64 bits respectively. The trusted catalog's
dual-target Shell release now uses the ordinary APK; `policy.shell-mode.platform` separately
packages settings-driven execution identity. Callback hosts use an SDK-only policy adapter.
The trusted catalog also publishes Ubuntu with an ARM 64-bit target; host-aware staging checks
the system, process architecture and minimum version before reading the payload.
Pass `-PpackageTargets=<targets.json>` to declare narrower support. iOS targets are supported
by the generic metadata format; kcode currently loads desktop JARs and Android APKs.
See [native execution](../plugins/native-execution/README.md).

The filesystem package selects different desktop and Android entry classes. Its desktop
artifact is `native-filesystem:packagedDesktopJar`, which merges the explicit private
`capability-providers` adapter and rejects duplicate entries; Android packages that same
private project explicitly. Neither artifact embeds the SDK. The desktop host supplies
the workspace path as an initial import configuration; updates preserve saved configuration.
The Android entry leases its application context from host inputs. The filesystem package
also remains available in native hosts with default product composition disabled, matching
the former native feature mount.

Feature archives contain their own settings forms and mount them as child contributions. No `ui-settings` archive or standalone setting-item package is shipped.
