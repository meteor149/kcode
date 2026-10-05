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

The shipped template uses `kcode.base`, `kcode.agent` and `kcode.default-ui`, in that order.
The catalogue supplies available code independently of the instance tree. Product providers
are allocated only after package and configuration preparation. A custom composition can
omit default UI or leave consumers waiting for required providers.

## Documents and restart

Desktop metadata lives in `.kcode/profiles` by default; Android uses
`filesDir/cordis_profiles`. Each Profile directory contains editable `profile.json` and
immutable `generations/<document-id>.json` files. Root `.profile-state.json` is the single
authority for visible Profiles, generation history and current selection. Generation files
are synced and staged before atomic replacement of that authority; unreferenced files are
never adopted as successful commits.

A format 2 committed document contains the definition, frozen bundle layers, verified
package lock and local runtime snapshot. Publication uses generation compare-and-set,
repository serialization, a file lock and a synced atomic file replacement. Format 1
documents remain readable and upgrade on successful publication. Legacy `committed.json`
and `selection.json` are imported once and remain read-only migration evidence. Existing
authority corruption fails closed rather than falling back to those legacy documents.
`ProfileGenerationRepository.state()` reads the selected generation and revision together;
`commitAndSelect` compares both the target generation and authority revision before publishing
the new generation and selection in one operation. Runtime switching must still coordinate
this publisher with staged activation, failure recovery and stable host facades.

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
to an encrypted MMKV identity and history to a scoped Room database. Android workspace
scope enforcement remains pending. Caller-supplied stores are borrowed and retain their own
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

Profile editing/activation commands, live selection/switching,
external bundle import, credential-safe export and management/recovery UI remain incomplete.
Desktop tests cover real startup, persistence and restart. Android compilation and APK
assembly establish build compatibility; they do not prove device loading or data isolation.
