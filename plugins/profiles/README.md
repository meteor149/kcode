# Profiles

Portable named plugin compositions for Kcode. `ProfileDefinition` records ordered bundle
references, user operations and logical data scopes. `ProfileCompiler` delegates operation
interpretation to Cordis's detached composition engine, so preview and activation can share
the same semantics. It does not import modules or allocate providers.

Portable definitions, entries, bundles and operations are SDK types in
`plugins/api`, under `ai.meteor.kcode.plugin.api.profiles`. This module retains the private
compiler, native activation, repository and migration implementations. API 65 exposes active
declaration reads/edits through `AgentPluginManager`; generation-checked edits use the runtime's
managed module/tree publisher. Returned intent and incoming operations are detached from caller
collections. Context operations replace present fields and clear explicit configuration when
given empty maps.

API 66 adds native manager catalogue/draft/clone/delete/preview/history/activation commands.
Draft writes and activation compare repository revisions. Explicit activation chooses committed,
draft or historical intent; history restoration appends a new generation. Clone drafts retain a
local frozen source recipe but never copy settings/history/workspace data. Preview prepares and
verifies package intent without mounting providers; ConfigValidator and service readiness remain
activation checks.

Layer precedence is bundle order, profile, machine and launch. Configure replaces the whole
configuration; an absent entry config uses the module default, while explicit JSON null is
preserved. Configuration kinds retain SDK scalar and Unit identities during migration.
Replacement supports an expected module
identity guard. Package verification, credential resolution and platform adaptation belong
to activation, not compilation.

API 68 adds positioned `Insert` and `Move` operations through the same compiler. Null parent
selects the root, null position appends, negative indexes count from the remaining list's end,
and bounds clamp. Move positions apply after removing the target. Invalid parents and cycles
produce layer/operation diagnostics. Runtime tree transactions recreate changed-parent branches
in their destination context while retaining same-parent reordered resources.

`FileProfileRepository` shares its JVM implementation between desktop and Android. Drafts
are independent of committed generations and use immutable documents with authority pointers.
Legacy `profile.json` and format 1 authority records remain migration inputs; successful mutation
upgrades the authority to format 2. Failed draft publication leaves the previous pointer readable.
Each committed JSON document atomically contains
the definition, frozen bundle layers, verified package lock and local runtime composition. Publication uses a
compare-and-set generation check, process serialization, a file lock and a synced temporary
file followed by atomic replacement. Immutable generation documents are referenced by one
`.profile-state.json` authority holding history and selection. A corrupt existing commit or
authority fails closed without rewrite. Legacy commits/selection import once without modification.

`ProfileGenerationRepository` adds consistent selected-generation/revision reads, published
history and atomic `commitAndSelect` with generation and revision checks. Unpublished files
cannot become recovery candidates. Removing a Profile withdraws its authority record while
retaining unreachable metadata for separate reclamation; recreation starts a new history.
Native runtime switching coordinates staged activation with this atomic publisher.

Native preparation can opt into `stageSwitch` for an explicit target. The session stages
startup intent/snapshots without saving drafts, advancing history or changing selection.
`publishPreparedSwitch` performs joint publication after allocation/settlement/frame preparation;
later writes use the ordinary durable path. Rejected publication remains retryable, while
discarded sessions reject further writes. Hosts own candidate closure, task admission and
stable facade switching; those obligations are not implemented by the metadata session.

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

`ProfileStartupFactory` prepares a deployment before product allocation. Native factories
use `prepareNativeProfileActivation` to select an explicit ID, the saved selection, or the
`native` template, in that order. Format 2 generations always serialize their version and
contain every referenced bundle. Format 1 commits, including documents that omitted their
default version, are readable and acquire frozen bundles on the next successful commit.
Restarting retains previously verified release identities instead of silently upgrading
them from distribution offers. Machine overlays configure every matching package instance
without putting local paths into portable intent.

The shipped factories bind settings/history/workspace scopes on both native platforms.
Android App shell and Ubuntu share the scoped workspace with file tools; app-private scopes
reject ADB execution before authorization. Root execution remains unverified. Caller-supplied
stores remain borrowed. Native switching and active declaration edits are implemented; complete
management/recovery UI, external bundle import and credential-safe export remain under development. See
`docs/profiles.md` and `docs/profiles-implementation.md`.

Resolved profiles retain machine and launch operations separately from portable definitions.
Runtime enable transactions recompile with those original layers, so saved user changes
cannot displace a launch policy. `AgentPluginManager.setEnabled` and enable-only
`applyChanges` publish Profile operations and the local snapshot together. Native hosts
regenerate machine configuration for changed intent through `ProfileActivation.machineConfiguration`.
Imports, code replacement and removals use joint native module-generation and tree transactions.
Candidate exports are borrowed by instance bindings until successful publication or completed
restoration; portable metadata never contains these runtime exports. See the Profile guide for
instance/package removal semantics and the remaining management/switching work.

Plugin API 67 consumers inject `KcodeProfiles`; its native
runtime bridge delegates these metadata contracts and submits composition commands to a
host-owned queue. This module does not own product UI or the command execution scope.
