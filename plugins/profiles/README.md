# Profiles

Portable named plugin compositions for Kcode. `ProfileDefinition` records ordered bundle
references, user operations and logical data scopes. `ProfileCompiler` delegates operation
interpretation to Cordis's detached composition engine, so preview and activation can share
the same semantics. It does not import modules or allocate providers.

Layer precedence is bundle order, profile, machine and launch. Configure replaces the whole
configuration; explicit JSON null is preserved. Replacement supports an expected module
identity guard. Package verification, credential resolution and platform adaptation belong
to activation, not compilation.

This module is under development; persistent profiles and runtime integration follow the
implementation phases in `docs/profiles-implementation.md`.
