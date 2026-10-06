# Profiles

SDK 72 exposes portable exchange through `KcodeProfiles.client.importPortable` and
`exportPortable`. Requests carry the catalogue revision. Import creates a new draft, retains
frozen bundle/package intent and resets data scopes; it never activates code. Export accepts
committed/historical targets and requires host feature-schema review for opaque values. The
native host reads feature schemas from the generation's verified locked package manifests;
missing rules and unknown fields/codecs deny export, and consumers cannot bypass it. These
metadata calls work in Ready and RecoveryRequired, follow bridge operation ownership and reject
calls after withdrawal. Activation remains a separate host-owned command. The optional settings
screen provides native file dialogs. Goal and Web Search declare Unit configuration rules;
Localization also declares portable dictionary JSON. Other feature schemas and real portable
archive acceptance are still being integrated.

Profiles describe plugin instances as portable data. Compilation applies ordered bundle
layers over an empty root, then Profile operations, machine configuration and launch
overrides. Compilation and runtime Include use the same Cordis composition semantics.
Configuration replacement is whole-value replacement. Omitted configuration requests the
module default; explicit JSON null remains distinct. Group entries preserve tree structure,
injection, interception and local or named service isolation.

## Native startup

The shipped `kcode.base`, `kcode.agent` and `kcode.default-ui` Bundle layers declare explicit
module membership. Available platform modules retain catalogue order within each layer;
missing platform variants are omitted. Undeclared default modules fail preparation instead
of being assigned by their ID prefix. This restriction belongs to the shipped distribution,
not the extensible plugin/module catalogue: alternate code remains selectable through Profile
operations without becoming an implicit default instance. Existing frozen generations keep
their original Bundle snapshots.

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
runtime rather than a retained old owner. Each allocation receives fresh host inputs.
Native hosts also expose explicit activation through the SDK plugin manager.

The suspending Profile-host factories retain host-owned management in RecoveryRequired when
initial Profile preparation or product allocation fails after native catalogue construction.
No initial product runtime is required to query metadata, repair a draft or submit activation.
Failed startup resource retirement is retained and must complete before recovery allocates a
target; incomplete initial tree retirement may require process restart. Cancellation closes
the unbound command gateway and joins allocated resources rather than returning a recovery host.
Invalid native module-factory configuration remains a construction error.

The compatibility createDesktopKoogChatRuntime/createAndroidKoogChatRuntime functions still
require successful initial startup: they close a failed host and rethrow its startup failure.
The shipped applications use the suspending Profile-host factories and the host-linked
`profile-recovery-ui` surface. Startup failures after catalogue construction show their cause,
saved Profile choices and an editable JSON definition. Users can save/discard repairs, activate
saved intent, or save and activate. Committed selections are read from their exact generation;
drafts do not require compiling the broken tree. The surface preserves dirty write revisions
and never queries product modules. Ready roots retain their own renderer and theme.
Recovery also exposes saved historical generations. Activating history appends a new generation
from its frozen Bundle/package recipe, without an implicit draft write. Historical JSON is
read-only in both management surfaces; clone it to a new draft before editing. Host-provided
distribution templates can create a repair draft under a new ID. This create-only operation
retains the original Profile/data and uses separate settings, history and workspace scopes.
The draft is activated explicitly. A failed definition/template query does not hide other
available catalogue/template choices. Templates are private native-host metadata; writes and
clones still use the neutral management contract. No shared SDK contract changed in this phase.
The host surface's recovery/edit/activation flow and English/Chinese defaults were verified
in an actual Android window on a physical ARM64/API 36 device under SDK 71. This targeted
case does not establish desktop rendering or every touch/keyboard route.
Failure before catalogue construction,
bundled-package staging failure and repair of corrupt repository authority remain outstanding.

In `RecoveryRequired`, host-owned catalogue/draft/clone/delete/preview/history commands remain
available without the product tree. The catalogue reports no active Profile; the durable selection
remains unchanged. Ordinary agent work and active-composition commands remain closed. SDK
`activateProfile` can prepare and activate a committed, draft or historical target from this state;
the host also offers `recoverTo(id)` for normal committed/draft startup selection. Recovery uses
fresh resources and appends a generation only after successful allocation and publication.
Failed or cancelled attempts retain `RecoveryRequired` and the previous authority.

Active Profile edits also close execution admission when tree or module restoration fails.
The runtime rejects subsequent ordinary calls and withdraws its prepared UI projections;
the host removes the failed runtime from its current view, enters RecoveryRequired and retains
the owner for retirement before explicit recovery allocates a target. A failed publication
whose old composition is restored successfully remains an ordinary command failure and keeps
the host Ready. Durable selection and generation history are unchanged by failed edits.

Owners whose closure failed are retained and recovery calls their closure boundary again. No
candidate allocation or automatic old-runtime restoration overlaps such an owner. Cordis Fiber
cleanup failures are terminal for that Fiber: every disposer is attempted, its failure is retained,
and subsequent disposal reports the failure without rerunning releases. Runtime closure likewise
retains its completion result. Recovery therefore cannot certify a failed provider's retirement
simply by calling close again; a process restart or explicit resource recovery mechanism is needed.
This differs from a retryable host adapter failure after a successful runtime close. This host command path does not
yet provide an independent recovery UI or recover failures before initial host construction.

Portable definitions, entries, bundles and operations are shared SDK contracts under
`ai.meteor.kcode.plugin.api.profiles` (introduced in Plugin API 65). The stable `AgentPluginManager` exposes
`currentProfile()` and `editProfile(ProfileCompositionEdit)`. An edit compares the active Profile
ID and generation before any withdrawal, compiles operations through the ordinary layer stack,
retains required package dependencies and publishes through the existing module/tree transaction.
It supports insertion of independently configured instances and groups, configuration replacement,
enable/disable, replacement, removal, movement/ordering and context configuration. Context fields omitted from an
operation remain unchanged; an empty map clears explicit inject/intercept/isolate configuration.
Incoming operations and returned intent are detached from caller collections. An empty edit
returns the current generation without publishing.

Plugin API 68 adds `Move(target, parent, position)` and positioned insertion. Null parent moves
to the root; null position appends. An index is evaluated after removal, with zero-based positive
positions, negative positions counting from the end and out-of-range positions clamped. A group
move keeps its subtree. The composer rejects missing/non-group parents and self/descendant cycles
without applying the offending operation. Previews and activation share this interpretation and
retain layer origins for parent/order edits. The tree transaction retires changed-parent branches
before updating groups and recreates them in the new context, independently of group order.
Same-parent ordering retains effects/resources. Failed publication or cancelled allocation
restores the previous hierarchy and realms; successful movement becomes restartable intent.

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
portable definition does not. The initial default management surface is described below;
rendered management acceptance and independent recovery UI remain outstanding.

The shipped template uses `kcode.base`, `kcode.agent` and `kcode.default-ui`, in that order.
The catalogue supplies available code independently of the instance tree. Product providers
are allocated only after package and configuration preparation. A custom composition can
omit default UI or leave consumers waiting for required providers.

## Default management surface

The optional [ui-profiles package](../plugins/ui-profiles/README.md) contributes a settings
section through KcodeProfiles and KcodeUiSlots. New native templates select kcode.default-ui
version 2 with this package. Frozen version 1 commits keep their original composition and
can explicitly insert provider.ui.settings.profiles when available; startup does not silently
upgrade their instance trees.

The initial screen supports creation, cloning, source selection, JSON definition editing,
revision-checked draft saving, discard, preview, effective tree/diagnostics, module catalogue,
history activation, confirmed deletion and host command status. Editing a definition can change
Bundle order, name, operations and data scopes. Refresh retains unsaved input and its original
authority revision; it cannot authorize overwriting another writer. Deletion confirmation is
bound to the selected target and revision. Activation requires a saved, verified diagnostic-free
preview. The package follows the configured app language and owns resource fallbacks.

File exchange uses native Desktop/Android pickers. Enter a new Profile ID (the display
name defaults to that ID), then import a UTF-8 JSON document up to 2 MiB. Import captures
the repository revision before selection and creates a draft only: it does not prepare
packages or activate the tree. Save or discard unsaved editor text before file exchange.
Export requires a committed or historical selection and completes host schema review
before opening the save picker. Unreviewed opaque values are rejected; feature packages
declare permitted fields/codecs in their verified metadata. Desktop replacement is atomic; Android document-provider
writes are not guaranteed atomic. Picker cancellation changes no Profile metadata.

Structured tree forms append SDK operations for insertion/grouping, position/parent movement,
configuration codecs, module replacement, enable/disable, removal and service scope editing.
Bundle controls add/remove/reorder references, and rename updates the draft name. These save
revision-checked draft intent and reload the host preview without changing the active runtime.
Forms cannot overwrite unsaved raw documents or edit historical intent without cloning.
Duplicate identities, unavailable modules, invalid scalar values and ancestry cycles reject
form submission. Final structure/package diagnostics remain owned by the shared compiler and
host preview. Field-source inspection uses the preview's origins. Saved intent remains visible
when a later preview query fails; late asynchronous callbacks are ignored after withdrawal.

Queries and observers are cancelled/joined on withdrawal. Accepted activation remains owned
by the host, and recent command state survives UI reconstruction. This screen does not supply
the independent recovery entry point when the product root fails. Unsaved raw edits defer
section return, system back and sheet dismissal before exit animation. Saving and leaving
publishes a draft without activating it; invalid documents or revision conflicts preserve
the editor. Discard restores the saved document, while continuing retains edits. Confirmation
captures the document/target/revision and cannot discard newer input. Forced withdrawal bypasses
the prompt and never invokes pending navigation. Saving keeps the prompt open until its durable
result. Android private-APK rendering checks cover editing, system-back confirmation, invalid
save retention, save/discard return and live English/Chinese labels. Feature XML generates
private text defaults rather than reading them from host APK resources. Confirmation actions
use one measured column and their text bounds are checked for clipping. Import/export,
independent recovery UI, desktop rendering and broader device acceptance remain outstanding.
This device case does not establish all tree forms, activation, keyboard/touch navigation or
all settings dismissal routes.

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
are host-supplied code and do not claim archive hashes or portable binary verification.

Desktop and Android factory entry points accept moduleFactories: a map from stable module IDs
to functions producing lazy KcodePluginMount definitions. The map is detached at construction;
each runtime allocation invokes the factories again. Keys must match exported descriptor IDs
and cannot shadow default modules, packaged releases or infrastructure. Factory functions must
not allocate provider resources; providers allocate and collect cleanup during apply.

Alternates contribute available code without changing shipped Bundle templates or creating
default instances. The host's selectProfileModule(packageId, moduleId, expected) checks active
Profile identity/generation and applies the existing typed transaction. Its expected value comes
from currentProfile(). Selection is retained through native switching and restart when the same
catalogue is supplied. Hosts do not serialize functions or retain previous runtime mounts to
recreate products. Plugin API 67 exposes the `KcodeProfiles` service for injected management
consumers. Its client reads metadata and the current module catalogue; `submit` enqueues
activation, active edits or module selection without waiting for the submitting plugin's
withdrawal. The host owns accepted work across product reconstruction. Cancelling a handle
observer does not cancel the command; explicit handle cancellation before publication can
abort it, while cancellation after publication reports its committed result. Old clients reject
new calls after withdrawal. The queue accepts at most 32 pending/running commands and retains
bounded recent status history. At initial allocation the service reports Starting and rejects
requests until host binding; plugin apply must not wait for readiness. Native hosts retain
`profileCommands` for metadata/activation in RecoveryRequired when no product tree is available.
The initial default catalogue UI consumes this service through the optional ui-profiles package.

External bundle import, credential-safe export and
rendered management acceptance and independent recovery UI remain incomplete.
Native runtime APIs support live selection/switching on both platforms. Desktop tests cover
actual package startup, switching, persistence, rollback and restart. Android tests on
an ARM64 API 36 device cover actual APK providers, scoped MMKV/Room/file data, App and Ubuntu
workspace binding, stale services, selection restart, failed allocation recovery, typed alternate
catalogue selection/restart and scoped
ADB rejection. They do not establish root or Shizuku authorization. See [verification](verification.md)
for evidence boundaries; rendered management acceptance and independent recovery UI are still required. Explicit
recovery commands are tested on Desktop and compiled for Android; device recovery-after-restoration-
failure behavior requires its own instrumentation evidence.
