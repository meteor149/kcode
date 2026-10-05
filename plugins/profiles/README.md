# Profiles

Portable named plugin compositions for Kcode. `ProfileDefinition` records ordered bundle
references, user operations and logical data scopes. `ProfileCompiler` delegates operation
interpretation to Cordis's detached composition engine, so preview and activation can share
the same semantics. It does not import modules or allocate providers.

Layer precedence is bundle order, profile, machine and launch. Configure replaces the whole
configuration; explicit JSON null is preserved. Replacement supports an expected module
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

This module is under development; package resolution, migrations, runtime integration and
management UI follow the implementation phases in `docs/profiles-implementation.md`.
