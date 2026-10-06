# Desktop platform and Profile startup

This module supplies verified JAR loading, native package preparation and SDK host adapters.
`createDesktopKoogChatRuntime` accepts `profileId` and `homeDirectory` (default `.kcode`).
An explicit Profile ID takes precedence over saved selection and the `native` template.
The desktop application accepts `--profile <id>` or `--profile=<id>`.

The factory prepares bundles, package locks and machine configuration before product
allocation. Profile metadata lives in `<homeDirectory>/profiles`, verified deployments in
`<homeDirectory>/plugins`. Settings/history use logical data scopes; workspace configuration
uses the legacy workspace or a directory under `<homeDirectory>/workspaces`.
Caller stores are borrowed and keep their caller-defined scope and lifetime.

Committed bundle snapshots and release locks govern restart. Edited drafts do not replace
successful generations. `AgentPluginManager` updates active Profile intent through native
module/tree transactions, including verified archive import and independent instance removal.
`createDesktopProfileHost` constructs a stable `KcodeProfileHost`; the existing runtime factory
returns its chat/manager/application facades with the host as owner. `switchTo` stages the target,
closes the previous product, publishes generation/selection together and changes the delegates.
Failed target activation restores the old locked generation. Active work requires explicit
cancellation/join, and failed closure/restoration closes execution admission. Each allocation
receives fresh host inputs; the retained catalogue contains IDs rather than old mounts/resources.
The Plugin API 66 manager exposes catalogue/draft reads, revision-checked draft writes,
cloning, deletion, preview, history and activation of committed/draft/historical intent.
Preview prepares the deployment without mounting providers. Historical activation appends a
new generation; clones retain code intent and use separate business data scopes by default.
Both native applications link independent recovery UI; optional default Profile management
is provided by `ui-profiles`. See the Profile guide for behavior and verification limits.
After failed restoration, the retained host admits metadata/preview and explicit SDK activation
without a product runtime. recoverTo(id) retries normal recipe activation after retiring any
owner whose cleanup previously failed. Further closure failure prevents new allocation.

Both runtime/host factories accept moduleFactories keyed by distinct stable module IDs. These
factories return lazy definitions per product allocation and never add default Bundle instances.
selectProfileModule(packageId, moduleId, expected) on the stable host retains instance intent
through the Profile transaction and checks expected active identity/generation. Supply the same
catalogue after restart; missing selected code rejects preparation. Default/package collisions
and factory descriptor mismatch reject before product allocation. Host code is borrowed and
does not claim verified archive identity; resources still allocate/close in provider effects.

Native hosts now own a bounded `ProfileCommandGateway` and expose Plugin API 67 `KcodeProfiles`
through a fresh infrastructure bridge in each product tree. Initial apply sees Starting until
the host binds; do not await readiness from apply. Injected clients submit detached activation,
edit or module-selection commands synchronously, then observe their host-owned handles. Accepted
work survives the submitting provider's withdrawal. Old clients reject new calls; explicit
cancellation after publication retains the committed result. The host's `profileCommands` client
remains available for metadata/activation after failed restoration. Default Profile UI remains optional.

Directory preparation, bundled staging and module factory/catalogue errors now return a
`RecoveryRequired` host before any product allocation. Repository and legacy store directories
open on use rather than in constructors. Host metadata commands remain available when storage
is readable, and report unavailable storage without deleting/resetting it. Explicit preview
or activation retries unsuccessful native preparation after the underlying cause is repaired.
Preparation shares catalogue validation with runtime creation and never applies providers;
only a complete successful snapshot is retained. Failed template queries are retried after
preparation succeeds. Cancellation still closes the gateway and propagates; compatibility
runtime factories still require a ready product and rethrow failed startup.
