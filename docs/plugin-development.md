# Plugin development

Read the [architecture](plugin-architecture.md) and [Profile guide](profiles.md) before changing
public contracts or composition. Shared code belongs in `commonMain`; use the narrowest
platform source set for actual OS loading, databases, windows and execution resources.

## Contracts and allocation

1. Put cross-package neutral types and service keys in `plugins/api`. Optional default UI
   extensions may also consume `plugins/default-ui-api`. Import contracts, not provider classes.
2. Implement `Plugin<C>` with a `ConfigValidator` for deployable configuration. Validate without
   opening resources. Configuration defaults belong to the feature, not the repository or host.
3. Allocate resources during `apply`. Register services/contributions with stable nonempty IDs
   and collect every disposer in the owning effect. Release partial allocation if setup fails.
4. Declare required services with `inject`. Missing services suspend consumers until recovery;
   do not construct fallback product singletons to bypass that lifecycle.

Typed `kcodePlugin` mounts expose a lazy `export` for declarative instances. An omitted Profile
configuration uses the typed default; explicit configuration retains its scalar/Unit/JSON codec
identity and validator. Opaque legacy mounts support their default configuration only. Portable
Profile data cannot contain Kotlin implementation objects, callbacks or arbitrary graphs.

Native alternate module factories return lazy definitions and may be consulted during metadata
preparation before any product exists. Do not allocate resources in those factories. Successful
preflight definitions are consumed once by the first product; later allocations obtain fresh
definitions. Directory/catalogue preparation failures leave a host recovery surface and can be
retried explicitly after their cause is repaired. Preview never applies provider effects.


## Portable Profile configuration

SDK 74 exposes `importArchive(ProfileArchiveImport(...))` and
`exportArchive(ProfilePortableExport(...)) { reference -> ... }`. Import references remain
caller-owned until return; exported references remain host-owned and expire when the consumer
returns. Export approval is selected by the host from verified feature metadata. Callbacks
must not close their runtime or unload their own provider. See [committed archives](profile-archives.md).

SDK 73 adds `ProfileManagementClient.importBundles(ProfileBundleImport(archives, newId,
expectedRevision, displayName))`. Each `ProfileBundleArchiveReference` contains a temporary
local path and its SHA-256, retained by the caller until the call returns. Native hosts
verify/stage the ordered archive stack and publish a create-only isolated draft. This
operation is owned by the management bridge and remains separate from activation. Input
locators are never portable data. See [Bundle archives](profile-bundle-archives.md).

A feature may declare `src/profile-export/<package-id>.json`. The native build includes it
in that release's `ai.meteor.kcode.profile-export` manifest extension. External publishers
can emit the same extension directly. The host verifies the selected locked archive, native
artifact and SDK identity before reading it; it never starts the feature to obtain approval.
Unknown packages/fields/codecs remain denied, and newer installed releases cannot review
historical values. Missing schema does not authorize opaque configuration.

```json
{
  "formatVersion": 1,
  "fields": {
    "config": {
      "unit": { "type": "null" },
      "json": {
        "type": "object",
        "properties": { "enabled": { "type": "boolean" } },
        "required": ["enabled"]
      }
    }
  }
}
```

Field keys are `config`, `inject/<service>` or `intercept/<service>`, followed by explicit
configuration codec rules. Supported rules are null, boolean, integer, number, enum (`values`),
string (`maxLength`, 1..65536) and object. Objects deny undeclared properties unless their
`additionalProperties` explicitly contains another rule. Nested rules are limited to 16
levels. Unknown schema members/types and invalid required properties reject export.
Numbers require finite inclusive `minimum` and `maximum` bounds. Integers may declare both
bounds; bounded integer endpoints must be whole numbers within the exact JSON integer range
(-9007199254740991..9007199254740991). Numeric strings are never coerced.
Every shipped provider declares explicit Unit export permission in its owning module.
Model temperature bounds, subagent concurrency and UI theme configuration additionally
declare bounded JSON fields. Theme colors use string rules with maxLength and the fixed
`format: "hex-color"`; RGB/ARGB hex values are accepted and arbitrary strings are rejected.
Unknown formats fail closed; arbitrary regular-expression schemas are not supported.
Other JSON configuration requires its own feature declaration. Implicit defaults do not
contain opaque configuration and do not need to be converted to explicit Unit patches.
This is export permission, not a replacement for ConfigValidator. Do not declare credentials,
host paths or arbitrary implementation graphs portable. Feature fields that contain text
are the author's responsibility; the host does not infer confidentiality from key names.

## Withdrawal and asynchronous work

Own asynchronous operations explicitly, using `PluginOperationOwner` where appropriate.
Cancel and join them before closing resources. Provider callbacks cannot unload their own
provider or close their runtime. Callback registration and disposal must tolerate replacement:
an old disposer cannot remove a new callback, and stale callbacks cannot start work.

Explicit Cordis Fiber withdrawal cancels suspended allocation and waits for collected cleanup.
Keep `apply` cancellable and move ongoing background work into owned operations. Blocking
native work needs a platform-specific cancellation strategy. Attempt every release even if
another fails; preserve durable data when closing a repository or settings provider.

Profile management consumers inject `KcodeProfiles` and call `client.submit(command)` to
change composition. Acceptance is synchronous and execution belongs to the host, so submitting
from a provider callback cannot unload that provider within its own owned call. Observe the
handle from owned UI/background work; observer cancellation does not cancel accepted work.
Use `handle.cancel()` for explicit cancellation. After publication the result remains Succeeded.

SDK 72 metadata exchange uses `client.importPortable(ProfilePortableImport(document, newId,
revision))` and `client.exportPortable(ProfilePortableExport(target, revision))`. Read the
catalogue revision first. Import creates an independent draft and must be activated separately;
it does not resolve packages or allocate providers. Export accepts committed/history targets,
keeps frozen bundles and excludes machine deployment state. Explicit opaque values are denied
unless the host has vetted feature-schema review; a consumer cannot provide its own approval.
Observe normal bridge operation ownership for these suspend calls, and do not expect an export
request to activate or snapshot unsaved editor changes. Native file UI remains separate from
the neutral data/command contract.
Initial apply sees Starting; defer queries/submission until readiness without blocking apply.
Withdrawn clients reject new requests, but accepted handles remain observable across switches.

## Packaging and composition

Use the Cordis packager and existing native release catalogue. A release requires compatible
SDK/framework identities, verified manifest/files, platform variant and content hashes.
Resources resolve from the verified deployment graph rather than a host APK fallback.
Plugin code and private dependencies remain private; only declared SDK/framework packages
share identity. An equal Plugin API number alone does not prove binary compatibility.

Install, replace, enable, disable and uninstall through the runtime's `AgentPluginManager`.
Do not edit the Loader or composition document directly. In Profile mode, enable targets are
instance IDs. Releases and instance trees are separate; removing one instance can retain code
used by another. Portable edits, machine configuration and launch policy use the same compiler.

The runtime's typed alternate-module command replaces a package reference with a distinct stable
module ID while retaining instance intent. Supply that selected module on restart; the declaration
cannot serialize implementation objects. Same-ID code upgrades use verified package replacement.
Native factories accept moduleFactories keyed by distinct exported module IDs. Return lazy
module definitions; allocate resources during apply. Factories run for every product allocation
and must not shadow default/native package identities. The stable host's selectProfileModule
checks the expected active Profile/generation before changing all references to a selected module.
Supply the catalogue again on restart; do not bypass it with retained runtime owners or mounts.

When changing a shared public ABI, review exports and package tests, update
`CurrentPluginApiVersion`, regenerate package metadata and document the change. This applies
to callbacks, default methods and configuration types as well as service keys.

## UI and persistence

Features own configuration forms, validation and optional settings contributions. Forms submit
through the settings command/session boundary. Use neutral text identities and feature-owned
localization. Shared controls belong to `libraries/ui`, use design tokens and `KcodeIcon`, and
receive labels/callbacks without accessing services, repositories or navigation.

Settings use the common store contracts and opaque feature namespaces. History uses structured
Room/SQLite repositories with suspending calls, transactional mutations and explicit migrations.
Scope selection is a host machine input; portable definitions use logical scope IDs. Caller
stores remain borrowed. Unloading a model provider preserves its saved identity and credentials.

## Validation and documentation

Use `kotlin.test` in the correct common/platform test source set. Lifecycle changes need
cancellation, stale-reference, withdrawal/recovery, replacement and failed-publication coverage.
Use real JAR/APK packages to verify shared/private identity and resource independence. Android
compilation does not establish device loading, Shizuku authorization or root execution.

Develop coherent phases, run affected checks at their boundary, and commit concise Conventional
Commit messages. Final shared changes require shared tests plus Desktop compilation and Android
assembly. Keep both root READMEs aligned and record entry points/configuration in module READMEs.
Link English topic guides through the [documentation index](README.md).


## Product execution admission (SDK 75)

Native runtime coordination publishes `KcodeExecution` independently of the selected product.
It is a shared SDK identity covered by `PluginHostApiPackages`, not a scheduler, model provider
or reserved Harness service. Providers that launch autonomous execution must enter
`KcodeExecution.admission.run` before allocating execution resources and remain inside it
until their owned child work and cleanup have settled. Never retain work outside that scope
or shadow this coordination service with a product implementation.

The shipped agent provider, generation runner and schedule dispatch capture this boundary
from `ctx.root` at allocation, independently of product service realms. Direct
plugin calls to the agent service and scheduled/history generation tasks therefore participate
in runtime composition checks, independently of the native host facade. Koog continuations
and in-process subagents remain structured children of the admitted agent operation. Providers
used outside a managed runtime retain their own allocation/operation ownership contracts.

Composition updates pause admission while validating, replacing and publishing. Active work
requires finishing first. Whole-runtime Profile switching can explicitly cancel active work;
cancellation joins its cleanup before target preparation/allocation proceeds. Retiring a
runtime permanently closes its boundary, so stale references cannot reopen execution.
Startup/candidate publication and other autonomous command/provider routes require their own
acceptance coverage; this contract does not make arbitrary detached plugin work owned.

Schedule rejection/cancellation returns false before accepting a due task. Dispatch runs in a
supervised child, so cancelling it does not permanently stop a scheduler retained after failed
Profile preparation. Provider withdrawal cancels the parent and continues to join all cleanup.


SDK 76 changes `ConversationExecution.startResponse` to a suspending request boundary.
Consumers must call it from owned suspending work and rebuild native packages. The standard
executor admits command/send/setup/regeneration preparation before state or persistence effects.
Do not substitute a read-only `isOpen` check: admission must own the work through its asynchronous
cleanup. A response handed to the generation provider retains that provider's independent scope.
