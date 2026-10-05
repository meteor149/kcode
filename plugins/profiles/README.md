# Profiles

Portable named plugin compositions for Kcode. `ProfileDefinition` records ordered bundle
references, user operations and logical data scopes. `ProfileCompiler` delegates operation
interpretation to Cordis's detached composition engine, so preview and activation can share
the same semantics. It does not import modules or allocate providers.

Layer precedence is bundle order, profile, machine and launch. Configure replaces the whole
configuration; an absent entry config uses the module default, while explicit JSON null is
preserved. Configuration kinds retain SDK scalar and Unit identities during migration.
Replacement supports an expected module
identity guard. Package verification, credential resolution and platform adaptation belong
to activation, not compilation.

`FileProfileRepository` shares its JVM implementation between desktop and Android. Drafts
are independent of committed generations. Each committed JSON document atomically contains
the definition, verified package lock and local runtime composition. Publication uses a
compare-and-set generation check, process serialization, a file lock and a synced temporary
file followed by atomic replacement. A corrupt existing commit fails closed without rewrite.

The package lock has no artifact paths or credentials; runtime snapshots retain local paths.
Selection only references committed profiles, and deleting the selected profile is refused.
Package caches and provider data are outside this repository and are never deleted with it.

`ProfileResolver` collects only referenced package releases and their dependency archive
hints. The existing native package resolver verifies the actual graph, platform variant and
ABI, and every retained release is reverified. Resolution retains separate entry identities
and group scopes; it does not allocate plugin instances or flatten the tree.

`prepareProfileBootstrap` prefers the last successful definition over editable drafts. Legacy
enable/configuration state and explicit uninstalls migrate without rewriting the old store;
an interrupted first migration keeps legacy installations available for retry.
`ProfileCompositionSession` adapts managed runtime snapshot publication to Profile generation
commits. Hosts must make `commitDefinition` their last fallible transaction step.

`ProfileActivation` passes a resolved tree and its composition session to the managed runtime.
`KcodePluginRuntimeConfig.profileActivation` selects declarative startup, with lazy builtin
modules supplied through `profileBuiltinModules`. Groups use the structural `core.group`
identity and require no package archive. Service injection and interception values are decoded
to Cordis context values; local and named isolation rules are retained.

Native controllers register release modules separately from tree instances. Per-instance
configuration and code origins remain independent, and package inventory rows use `package:`
identities while instance rows use their entry IDs. Runtime startup validates all configurations,
settles the tree and prepares application snapshots before atomically committing a generation.

This module is under development; default host bundle selection, Profile update/switching,
data-scope allocation and management UI follow the phases in `docs/profiles-implementation.md`.
