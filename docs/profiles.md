# Profiles

Profiles describe plugin instances as portable data. Compilation applies ordered bundle
layers over an empty root, then Profile operations, machine configuration and launch
overrides. Compilation and runtime Include use the same Cordis composition semantics.
Configuration replacement is whole-value replacement. Omitted configuration requests the
module default; explicit JSON null remains distinct. Group entries preserve tree structure,
injection, interception and local or named service isolation.

## Native startup

Native factories choose an explicit `profileId`, the repository selection, or the initial
`native` template. Desktop accepts `--profile <id>` and `--profile=<id>`. Android forwards
the `profile` Activity intent extra. These choose startup; they do not perform a live switch
or save selection by themselves.

Desktop and Android factories return stable facades owned by `KcodeProfileHost`.
`createDesktopProfileHost` and `createAndroidProfileHost` are the suspending construction entry points; `switchTo(id)` performs
an in-process switch and saves selection only after successful target activation. Active calls
and overlay leases must finish first, or the caller must request `cancelActive = true` to cancel
and join them. Target preflight precedes old-runtime withdrawal; failed allocation/publication
reconstructs the old locked generation without rewriting history. Failed old closure or failed
restoration refuses new work in `RecoveryRequired`. Diagnostics use the host's admitted current
runtime rather than a retained old owner. Each allocation receives fresh host inputs. Recovery UI
remains pending. Native hosts also expose explicit activation through the SDK plugin manager.

In `RecoveryRequired`, host-owned catalogue/draft/clone/delete/preview/history commands remain
available without the product tree. The catalogue reports no active Profile; the durable selection
remains unchanged. Ordinary agent work and active-composition commands remain closed. SDK
`activateProfile` can prepare and activate a committed, draft or historical target from this state;
the host also offers `recoverTo(id)` for normal committed/draft startup selection. Recovery uses
fresh resources and appends a generation only after successful allocation and publication.
Failed or cancelled attempts retain `RecoveryRequired` and the previous authority.

Owners whose closure failed are retained for explicit cleanup retry. No candidate allocation or
automatic old-runtime restoration overlaps such an owner. If closure continues to fail, recovery
continues to reject allocation; a process restart may be needed. This host command path does not
yet provide an independent recovery UI or recover failures before initial host construction.

Portable definitions, entries, bundles and operations are shared SDK contracts under
`ai.meteor.kcode.plugin.api.profiles` (introduced in Plugin API 65). The stable `AgentPluginManager` exposes
`currentProfile()` and `editProfile(ProfileCompositionEdit)`. An edit compares the active Profile
ID and generation before any withdrawal, compiles operations through the ordinary layer stack,
retains required package dependencies and publishes through the existing module/tree transaction.
It supports insertion of independently configured instances and groups, configuration replacement,
enable/disable, replacement, removal and context configuration. Context fields omitted from an
operation remain unchanged; an empty map clears explicit inject/intercept/isolate configuration.
Incoming operations and returned intent are detached from caller collections. An empty edit
returns the current generation without publishing.

Plugin API 66 adds catalogue/draft reads, revision-checked draft creation/update, cloning,
deletion, preview, history and activation. `ProfileTarget` explicitly chooses `Committed`,
`Draft` or `History`; only History includes a generation. `activateProfile` compares the
repository revision before preparing code and commits generation/selection only after the
candidate settles. Applying history appends a new generation rather than rewinding pointers.
The current runtime's generation is checked against its restoration recipe before withdrawal.
An active Profile cannot be deleted, even if startup has not saved a selection.

Clones retain frozen bundle/code intent in their draft base recipe and default to separate
settings/history/workspace scopes. They never copy business data. Preview uses the same
composition/preparation path without mounting providers or publishing metadata. It reports
structural/package preparation diagnostics, entries and field origins. `packagesVerified`
does not establish ConfigValidator success, service readiness or provider allocation; those
remain activation checks. Effective preview entries may contain machine paths, while its
portable definition does not. Public management UI and recovery UI remain outstanding.

The shipped template uses `kcode.base`, `kcode.agent` and `kcode.default-ui`, in that order.
The catalogue supplies available code independently of the instance tree. Product providers
are allocated only after package and configuration preparation. A custom composition can
omit default UI or leave consumers waiting for required providers.

## Documents and restart

Desktop metadata lives in `.kcode/profiles` by default; Android uses
`filesDir/cordis_profiles`. Each Profile directory contains immutable
`drafts/<document-id>.json` and `generations/<document-id>.json` files. Root `.profile-state.json` is the single
authority for visible Profiles, generation history and current selection. Generation files
are synced and staged before atomic replacement of that authority; unreferenced files are
never adopted as successful commits. Authority format 2 adds draft pointers and names;
format 1 authorities remain readable and upgrade on successful mutation. Legacy `profile.json`
is a read-only draft migration input. Draft replacement stages a new document before publishing
its pointer, so a refused publication cannot replace the visible draft. A draft can retain a
local frozen generation recipe for clone/reproducible preparation; it is not a portable export.

A format 2 committed document contains the definition, frozen bundle layers, verified
package lock and local runtime snapshot. Publication uses generation compare-and-set,
repository serialization, a file lock and a synced atomic file replacement. Format 1
documents remain readable and upgrade on successful publication. Legacy `committed.json`
and `selection.json` are imported once and remain read-only migration evidence. Existing
authority corruption fails closed rather than falling back to those legacy documents.
`ProfileGenerationRepository.state()` reads the selected generation and revision together;
`commitAndSelect` compares both the target generation and authority revision before publishing
the new generation and selection in one operation. The native host coordinator combines
this publisher with staged activation, failure recovery and stable host facades.

`prepareNativeProfileActivation(stageSwitch = true)` requires an explicit target and captures
the authority revision before resolving it. Its session keeps startup publication in memory;
it does not save a migration draft or advance a committed generation. After the target runtime
has settled and prepared its frames, `publishPreparedSwitch()` commits the prepared generation
and selection together. It then becomes an ordinary durable session for later mutations.
Repeated preparation advances only one generation. A failed publisher can be retried; a stale
authority must be discarded and prepared again. `discardPreparedSwitch()` withdraws metadata
publication but the host must still close candidate resources. The host must keep candidate
facades private until publication and coordinate old-runtime shutdown/recovery; this preparation
protocol alone does not provide a live Profile switch.

History APIs expose only published documents. Removing an unselected Profile withdraws its
record atomically; retained files cannot resurrect it on restart or recreation. Physical
metadata reclamation is separate from that publication. Provider data and package caches
are not removed. This protocol establishes process/restart consistency using atomic file
replacement; tests do not simulate sudden device power loss or every filesystem's durability.

Startup prefers committed intent to editable drafts and revalidates retained deployments.
Changing a draft, bundle offer or distribution release does not silently upgrade a committed
Profile. Package IDs identify releases; entry IDs identify independently configured instances.
Disabling an entry does not remove its deployment from the locked available code graph.

A previously selected release takes precedence over a same-ID builtin catalogue module.
Native caller overrides explicitly select their borrowed/in-process modules; mere catalogue
availability cannot undo a saved release replacement. Legacy direct JAR/APK descriptors
remain local in the runtime snapshot, retaining their hashes, API identity and dependency
graph. Native registration validates their files before allocation. They do not claim verified
archive lock records and require packaging before portable archive export can be supported.

First migration projects legacy enable state, explicit removals and portable configuration.
Host path configurations are regenerated as machine overlays rather than exported intent.
The legacy composition document remains available if initial activation fails.

## Data scopes

Settings, history and workspace use logical scope names. `profile` selects `profile-<id>`,
`legacy` preserves former locations, and another valid scope selects `shared-<scope>`.
The initial native template uses legacy settings/history scopes. New definitions default
to Profile settings/history scopes. Service isolation and persistent data scopes are separate.

Desktop binds settings/history paths under `profile-data/<scope>` and workspace directories
under `workspaces/<scope>`; legacy values retain former locations. Android binds settings
to an encrypted MMKV identity and history to a scoped Room database. Android binds scoped
file tools, App shell working directories and Ubuntu `/workspace` to the same directory under
`filesDir/workspaces/<scope>`. Legacy/default workspace selection preserves former behavior.
App-private scoped workspaces reject ADB execution before requesting authorization; they do
not silently fall back to the shared ADB workspace. Root paths use the configured directory,
but real root authorization, permissions and execution have not been verified by these tests.
Caller-supplied stores are borrowed and retain their own
storage location. Closing a provider or deleting Profile metadata does not delete durable data.

## Current boundaries

Managed startup validates configuration before allocating product resources, settles the
tree and prepares application snapshots before publishing the committed generation. Failed
publication restores resources and leaves the previous generation readable. The plugin
manager's `setEnabled` and enable-only `applyChanges` target Profile entry IDs and publish
portable Enable/Disable operations through the tree transaction. Missing IDs reject the
whole batch before withdrawal. Machine and launch layers retain their precedence; a saved
Enable cannot override a launch Disable. Group enable changes use the same compiler.
Package IDs and `package:` inventory rows are code availability, not enable targets.

The same manager now routes package imports, installs, replacements, removals and mixed
`applyChanges` batches through coordinated module/tree transactions. Candidate exports are
imported and all instance configurations validated before withdrawal. Each binding borrows
the correct release export; unchanged exports/configurations retain their existing binding.
Changed releases and their dependants acquire new exports. After successful tree settling
and frame preparation, the generation is published and the private native module transaction
is finalized under the same mutation owner. Publication or allocation failure restores the
old tree before releasing candidate code, then restores descriptors, inventory and frames.
Cancellation completes restoration without cancelling cleanup.

An install adds a default instance whose ID matches the package. Imports mount explicitly
requested releases; dependency releases provide available code without implicit instances.
Replacing a release updates all its configured instances without resurrecting a removed
default instance, retaining the other instances'
configurations. A supplied upsert configuration updates the matching default instance;
machine configuration still takes precedence. A removal targets an instance ID first; a
package ID without a matching instance removes instances referencing that package. Code
remains locked while another instance or deployment dependency retains it. Same-version
verified releases cannot be republished with different bytes. Unknown/conflicting batch
targets and inventory identity conflicts fail before application.

Code availability and locked releases do not change during an enable-only transaction.
Publication failure preserves the previous committed intent. Repository generation CAS
remains the authority for concurrent writers.

### Typed alternate modules

At the runtime implementation boundary, replacePlugin(packageId, replacement) selects a
distinct stable in-process module ID for every configured instance of packageId. It appends
ordinary Replace operations and uses the same composition, configuration validation, tree,
application snapshot and generation publisher as other Profile edits. Instance IDs, groups,
configuration, contexts and enable states stay intact. Candidate module availability publishes
only after successful generation publication; failed attempts retain old bindings and allow
retry with the same candidate ID.

The Profile stores the selected module reference, never a Kotlin implementation object. Restart
requires the host to supply that alternate module again; missing code is rejected before provider
allocation. Same-ID typed code overwrite is rejected because it cannot record a distinct code
selection; use verified release replacement for package upgrades. Borrowed in-process modules
are host-supplied code and do not claim archive hashes or portable binary verification. Native
factories still need a selectable alternate-module catalogue and stable host command exposure;
this runtime method alone does not complete native typed replacement across switching/restart.

External bundle import, credential-safe export, native alternate-module catalogue/exposure and
management/recovery UI remain incomplete.
Native runtime APIs support live selection/switching on both platforms. Desktop tests cover
actual package startup, switching, persistence, rollback and restart. Four Android tests on
an ARM64 API 36 device cover actual APK providers, scoped MMKV/Room/file data, App and Ubuntu
workspace binding, stale services, selection restart, failed allocation recovery and scoped
ADB rejection. They do not establish root or Shizuku authorization. See [verification](verification.md)
for evidence boundaries; management and independent recovery UI are still required. Explicit
recovery commands are tested on Desktop and compiled for Android; device recovery-after-restoration-
failure behavior requires its own instrumentation evidence.
