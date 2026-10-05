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

Named startup and manager mutations are implemented. Declaration editing, runtime switching,
external bundle import and management/recovery UI remain pending. See `docs/profiles.md` and
`docs/profiles-implementation.md` for behavior and evidence limits.
