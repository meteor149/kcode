# Plugin development

Read [plugin architecture](plugin-architecture.md) first. Module entry points and configuration
are documented in the corresponding `plugins/*/README.md`. The current shared ABI is API 34,
defined in [AgentPluginManager.kt](../plugins/api/src/commonMain/kotlin/ai/meteor/kcode/plugin/AgentPluginManager.kt).

## Dependencies and entry points

- Ordinary consumers depend on service contracts in `plugins/api`, declare required dependencies with `inject`, and must not reference concrete providers.
- Default UI extensions also depend on `plugins/default-ui-api`. Explicitly use `libraries/ui` when reusing basic controls.
- Plugin entry points implement `org.cordis.Plugin<C>`. The composition layer supplies `KcodePluginMount` and a stable descriptor ID.
- Put deployable parameters in `ConfigValidator`. Reject invalid configuration during candidate loading; request defaults and product policy belong to the provider.
- Put shared code in `commonMain` and platform implementations in the narrowest source set. [settings.gradle.kts](../settings.gradle.kts) is authoritative for module declarations.

Service providers allocate resources and publish services in `apply`. Consumers obtain
capabilities through services. Do not pre-create fixed tool registries, clients, or product
singletons in platform factories. Tests and alternative roots that do not need the default
product composition can use `profile.includeDefaults = false`.

## Registration and operation ownership

Collect every contribution's `register()` result in an effect. For example,
[UiPagePlugins](../plugins/ui-pages/src/commonMain/kotlin/ai/meteor/kcode/plugin/UiPagePlugins.kt)
assigns renderer disposers to the current Fiber, while
[SettingsCommandsPlugin](../plugins/settings-commands/src/commonMain/kotlin/ai/meteor/kcode/plugin/settingscommands/SettingsCommandsPlugin.kt)
uses an operation owner to protect the full load/validate/save operation.

Registration IDs must be nonempty and unique within their registry. Revocation must be
idempotent and protect newer registrations with the same ID. Copy registration information
when preparing snapshots and invoke providers outside locks. Revoked callbacks in older
snapshots must reject further execution. Asynchronous work needs an explicit owner;
revocation cancels and joins it rather than merely removing a registry entry.

Clean up partial allocations, define commit points for durable changes, and report cleanup
failures to callers. A provider callback must not unload itself or close its runtime.
Track borrowed and owned resources separately. Failure to clean up one resource must not
skip release of the others.

## Dynamic JARs/APKs

`DynamicPluginSpec` declares the ID, version, entry class, artifact path, SHA-256, dependencies,
configuration, and API version. Install or replace through `pluginManager`; do not modify
the Loader directly to bypass composition persistence or snapshot publication. Configuration
and the artifact graph must belong to the same generation. Failed candidates must not
overwrite committed metadata.

The host shares only public SDK/framework identities. External product implementations and
private dependencies load through independent ClassLoaders. The shared boundary is defined
by [PluginHostApiPackages](../plugins/api/src/commonMain/kotlin/ai/meteor/kcode/platform/PluginHostApiPackages.kt);
do not mark the entire product plugin namespace as parent-first. Do not bundle duplicate
copies of the host's shared ABI. Use real platform package tests as references for compile
dependencies and production loading. Matching the declared API version is a pre-load check,
not binary compatibility analysis; Kotlin/Compose compiler versions must also be compatible.

Resources, native libraries, and Ubuntu images in imported Android packages come from their
own verified deployment graph. `PluginCodeOrigin` records root/dependency artifacts. The
application Context supplies only system primitives; the host APK, resource table, and
native-library paths must not serve as fallbacks for external plugin resources. Deployments
hold APK/FD and operation leases. Failed verification, revocation, and repeated close must
release their own temporary files.

Cross-process capabilities use SDK Binder/PFD contracts and actual UID checks. App-UID
instrumentation, UID-2000 `app_process`, genuinely independent BinderProxy calls, Shizuku
authorization, and root execution provide different evidence. Test entry points and the
UID-2000 driver are described in the [verification guide](verification.md).

## Persistence, models, and localization

Access settings through `AppSettingsStore`. MMKV stores small scalar preferences; maintain
stable keys together with their DTOs. Use Room/SQLite for structured conversation data,
suspending DAOs, transactions for multi-table mutations, exported schemas, and explicit
migrations. Storage implementations, defaults, codecs, and database builders belong to
providers. UI must not call DAOs directly.

`ModelProvider(id)` is extensible. The legacy `entries` property is a built-in catalog/alias
list, not an allowlist. Adapters contribute model catalogs, display text, and connection
metadata; configuration policy reads the current committed catalog. Unloading a provider
must not automatically discard its saved ID or credentials. Localization owns dictionaries
and default/fallback policy; the neutral SDK shares only text identities and access contracts.

Localize new UI text. Callers supply labels/callbacks to generic components. Store and render
icons according to the [UI design system](ui-design-system.md).

## Delivery checklist

1. Document entry points, configuration, extension points, missing dependencies, and limitations in the README.
2. Test observable behavior: success, failure, cancellation, stale references, revocation/recovery, and replacement rather than module names alone.
3. Verify shared contract identity, private implementation identity, and resource independence using real JARs/APKs for loadable product plugins.
4. Verify failed persistence commits and restart recovery, actual UI rendering and unloading, and migrations for new schemas.
5. Check versions, shared exports, and documentation whenever the public ABI changes. Never commit credentials, generated databases, or device identifiers.

Maintain development rules in topic guides and module READMEs. Update the relevant
documentation whenever public contracts change.
