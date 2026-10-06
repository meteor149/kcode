# Profiles

`ProfileArchiveExchange` exports reviewed committed/historical intent with exact code archives
and prepares imports using the target host's verified native variant. Ordered frozen Bundles
and user operations survive transfer. Preparation publishes no metadata and allocates no
product providers. SDK 74 connects native transport through owned management commands and
temporary export leases, consumed by the optional Profile file UI.
See [committed archives](../../docs/profile-archives.md).

`ProfileBundleArchiveWriter` and the Desktop `packProfileBundle` Gradle task generate
Bundle containers from definitions, declared targets and verified code archives. Temporary
code snapshots are bounded and input ordering does not change deterministic code metadata.
Packing does not load native code; host SDK/ABI and combined layer/dependency checks remain
import responsibilities. See [publishing](../../docs/profile-bundle-publishing.md).

Native `ProfileBundleArchive` prepares independent data archives and ordered Bundle stacks
using Cordis container verification and the native package resolver. It returns frozen
portable metadata without mounting code or publishing a draft. Imported drafts locate code
by locked digest in the verified cache. See the [archive guide](../../docs/profile-bundle-archives.md)
for the format and publication boundary. SDK 73 `importBundles` checks revision before
preparation and at create-only draft publication; native hosts supply the verifier.

Host export review receives `ProfileExportValue` with module/package identity, instance identity,
configuration codec, source location, field and original JSON value. `ProfileExportPolicies`
selects detached module policies and denies unknown identities. Configure/context review uses
the current composed module; replacements must approve inherited values with the same portable
result. Module transitions are traced in declared Bundle order through the shared compiler,
including movement and removal/reinsertion. Reviews see every stored layer. Policy inputs/outputs
are detached, failures omit configuration contents and cancellation propagates. Hosts may construct
`ProfileManagement(repository, bundles, policies, prepare)`; neutral clients cannot supply approvals.
These implementation types do not change SDK 72. Native hosts use `ProfilePackageExportReviews`
to read declarative schemas from each selected generation's verified package manifest. Historical
exports use their frozen deployments, not newer offers or live product services. Missing schemas,
unknown fields/codecs and malformed rules remain denied. Goal and Web Search declare Unit-only
configuration; Localization also declares its portable dictionary JSON fields. Other explicit
feature configurations still require feature-owned declarations. Review policies are pure metadata,
independent of withdrawn product resources. See the schema dialect in the plugin development guide.

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

`ProfilePortableExporter` provides a host-side exchange boundary for committed generations.
The versioned JSON envelope contains user intent, frozen declared bundles and package locks;
it excludes the local runtime composition, machine/launch overlays and business data. Export
resets settings/history/workspace scope aliases to independent Profile scopes. It reviews every
explicit configuration, injection and interception value in every stored layer, including nested
entries and values hidden by later overrides. The default policy denies these values rather than
guessing whether a key contains a credential. Feature-schema reviews may supply portable values;
the host must vet those reviews. Rejection messages never include rejected configuration values.
Decode validates format, bundle completeness, lock structure and composition without loading code.
It does not prove package authenticity or authorize installation/activation. Host-side
`ProfileManagement.importPortable` publishes a create-only, revision-checked draft with independent
data scopes and its frozen exchange recipe; import never calls package preparation. Draft format 2
retains that recipe through editing, cloning and repository reopening. Format 1 remains readable;
new draft publication upgrades the envelope. A local committed generation replaces the imported
base after successful activation. Ordinary preview/startup/activation verifies required imported
archive hashes and complete version/variant/ABI/dependency identities, and cannot substitute a
same-name builtin. Edited intent may add new packages through ordinary verified offers or remove
unused locked packages. Frozen Bundle contents survive installed catalogue changes. Host-side
export accepts committed/historical targets and rejects unverified legacy local descriptors.
SDK 72 exposes this boundary through neutral client requests with catalogue revisions. The host
metadata admission boundary works in Ready/RecoveryRequired; a plugin bridge owns its calls and
rejects them after withdrawal. Clients cannot supply export approval callbacks. Feature review
policies, file dialogs and real portable package exchange acceptance still need integration.

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
