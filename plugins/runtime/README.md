# Managed plugin runtime

`KcodePluginRuntime` owns turn admission, product lifecycle, plugin manager commands and committed
application projections. `profileActivation` supplies a prepared Profile; `profileStartup` asks
the native host to prepare one from its lazy module catalogue before product allocation.
The initial tree uses independently configured entry bindings, structural groups and SDK code
origin metadata. Profile infrastructure mounts separately from the selected product tree.

`AgentPluginManager` commands serialize under the runtime owner. Profile mode writes portable
operations and a local snapshot in one generation. `ProfileModuleTransaction` holds candidate
exports while Cordis applies/settles the tree and the host prepares frames/catalogues. Old exports
remain available for rollback; candidate code closes only after restoration. Native controllers
finalize their private prepared module graph after durable publication. Provider callbacks cannot
reenter mutation or close the runtime. Existing turns must finish or be explicitly cancelled.

Unchanged code/configuration retains its binding. A release replacement updates its dependent
code graph and all affected instances without collapsing their configurations. Removing an
instance retains packages still referenced by another instance or deployment dependency. Runtime
binding keys are ephemeral host details and never enter portable intent. The module protocol is
host implementation, not part of the independently loaded plugin SDK.

`KcodeProfileHost` supplies stable facades and serializes complete runtime switches. Preparation
verifies the target and retains a locked old-runtime recipe before withdrawal. Active calls and
overlay leases require explicit cancellation/join. Failed target allocation/publication restores
the previous intent without rewriting history; failed closure/restoration refuses new work in
`RecoveryRequired`. Callback reentry is rejected, and cancellation after durable publication
retains the new runtime. Native factories must provide fresh resources through `ProfileRuntimeFactory`
and keep candidate facades private. Public Profile management and recovery UI remain separate.

Named startup, manager mutations and native switching on Desktop and Android are implemented.
The SDK manager also edits active Profile declarations with identity/generation checks through
the same module/tree publisher. Native stable managers now expose catalogue/draft/clone/delete/
preview/history commands and revision-checked committed/draft/historical activation. Explicit
activation uses the host's shutdown/recreation/recovery boundary, including same-ID activation.
In RecoveryRequired, host-owned metadata commands remain admitted and explicit SDK activation
can recover an available recipe. recoverTo(id) is an independent host command. Failed closure
retains the owner until successful cleanup retry; no new allocation overlaps unresolved owners.
Failure/cancellation leaves admission closed and does not publish a generation. Initial host
construction failures and independent recovery presentation still require implementation.
External bundle import and management/recovery UI remain pending. See `docs/profiles.md` and
`docs/profiles-implementation.md` for behavior and evidence limits.

replacePlugin(packageId, replacement) supports typed alternate modules in declarative runtime
mode. It records distinct module references through Replace operations, preserves every instance's
configuration/context/enable state and publishes through the normal tree/generation transaction.
Failed validation, allocation or publication retains the old implementation and module directory.
Restart must supply the selected alternate ID; arbitrary implementation objects are not persisted.
Same-ID in-process overwrite is rejected in Profile mode. Native alternate module factories and
stable host command exposure remain required; verified external release replacement is separate.
