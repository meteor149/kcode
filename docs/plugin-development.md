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

## Withdrawal and asynchronous work

Own asynchronous operations explicitly, using `PluginOperationOwner` where appropriate.
Cancel and join them before closing resources. Provider callbacks cannot unload their own
provider or close their runtime. Callback registration and disposal must tolerate replacement:
an old disposer cannot remove a new callback, and stale callbacks cannot start work.

Explicit Cordis Fiber withdrawal cancels suspended allocation and waits for collected cleanup.
Keep `apply` cancellable and move ongoing background work into owned operations. Blocking
native work needs a platform-specific cancellation strategy. Attempt every release even if
another fails; preserve durable data when closing a repository or settings provider.

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
