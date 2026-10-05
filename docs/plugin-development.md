# Plugin development

Read [plugin architecture](plugin-architecture.md) first. Module entry points and configuration
are documented in the corresponding `plugins/*/README.md`. The current shared ABI is API 60,
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

The [cross-platform package format](plugin-package-format.md) wraps platform-specific JAR/APK
variants under one logical release. `AgentPluginManager.importPackages` verifies/stages archives
through the `pluginPackages` provider and commits their dependency set in one composition
transaction. Native loaders receive resolved `DynamicPluginSpec` values rather than archives.

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

Access settings through `AppSettingsStore`. MMKV stores small encrypted preference documents;
maintain versioned snapshot envelopes and read-only historical key migration. Use Room/SQLite for structured conversation data,
suspending DAOs, transactions for multi-table mutations, exported schemas, and explicit
migrations. Storage implementations, generic codecs, and database builders belong to
persistence providers; configuration schemas/defaults belong to their features. UI must not call DAOs directly.

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

Register settings UI from the owning feature archive, using a child Fiber that requires
`KcodeUiSlots`. Collect the child's disposer without awaiting absent optional UI services.
Do not add feature-specific dependencies or fields to the generic page renderer/request.
Register command transforms through `KcodeSettingsCommands.updates`; their disposers withdraw
field handling and cancel/join the complete in-flight save transaction.

Model adapters targeting API 47 declare their vendor implementation dependencies directly
and include them in the private platform artifact. Do not obtain clients from the SDK or
share the vendor namespace with the host. Keep `LLMClient`, HTTP factories and other
exported protocols in the shared SDK identity; include required upstream implementation
dependencies, such as Bedrock's Anthropic model/serializer code, in the private closure.

For API 48 conversation presentation, return a `ConversationDecorationContent` with a
generic `Header` or `AboveComposer` position. The page owns placement and compact-layout
measurement. Borrow `ConversationPageContext.hazeState` when provided; do not require a
concrete page implementation. Optional presentation declares its own reactive child
dependencies and quiets retained presenters/renderers when withdrawn.

## Feature settings documents

API 55 exposes `StoredAppSettings.namespaces: Map<String, JsonObject>`. Give each feature
a stable namespace and keep its schema, defaults and validation with that feature. Merge
only its owned fields into the current transaction snapshot; preserve unknown fields and
other namespaces, including documents belonging to disabled features. Namespace IDs and
JSON keys are complete identities, not dot-separated paths. An explicit JSON null or empty
string differs from removing a key. Generic patches merge independent nested changes.

API 59 removes fixed legacy members. `StoredAppSettings.legacyValues` holds raw historical
JSON, preserving absent fields, explicit empty/null values and unknown roots. Features
interpret their own historical keys only when their namespace is absent. Persistence stores
no feature defaults and writes one v2 document rather than historical scalar fanout.
Feature-owned UI validation remains tracked in the audit; a generic storage write alone
does not prove that feature validation ran. Rebuild external packages for ABI 59.

When a draft creates a previously absent JSON object, `SettingsPatch` merges its fields
into the latest document instead of replacing concurrent additions. Empty objects are
created if needed without clearing objects already committed by another writer. Explicit
JSON nulls and arrays remain atomic replacement values; an explicit removed key is still
a deletion. This behavior also applies to nested objects and dotted field identities.

API 56 lets a localization catalog expose optional `LanguageSettingsPolicy` through
`languageSettings`. Supply it only when the feature implements preference configuration;
rendering-only catalogs use null. Keep its namespace schema, legacy interpretation, defaults
and choice validation in the feature. A root borrows that projection and uses the catalog's
default language when configuration is unavailable. Withdrawing the provider revokes retained
policy calls; closing a root projection does not close borrowed provider capabilities.

For conversation configuration controls, contribute `ComposerActions` from the feature's
optional default UI child. Use `ConversationPageContext.settingsEditor` to read the prepared
draft and submit a proposal; do not require generic pages to import a concrete feature,
mode enum, storage key or renderer. Withdraw both registration and its fallback dictionary,
and reject retained control callbacks after withdrawal.

## Settings mutation ownership

A feature owns its namespace, schema, defaults and validation. Register a
`SettingsMutationValidator` through `KcodeSettings.mutations` in a headless consumer
child which injects the settings service and any required feature policy. Collect the
registration disposer. Do not make the feature's entire provider depend on default UI
or the optional settings command service merely to enable validation.

Default and alternative roots submit proposals through `KcodeSettings.mutationStore`.
Its transaction reads current persisted data and validates the merged candidate before
committing. Validation of multiple changed namespaces is atomic, and each owner is held
through durable save. Unchanged unknown namespaces are preserved; changing an unowned
namespace is rejected. Persisted historical values remain read-only on this path.
