# Plugin architecture

Kcode is a Kotlin Multiplatform application composed from Cordis providers and consumers.
`settings.gradle.kts` is the module authority. `plugins/api` owns neutral domain, persistence,
agent, platform-input and open UI contracts. Concrete provider implementations remain in
their feature packages. Native hosts adapt platform facts and loading; they do not own
default product service instances.

## Composition and code identity

`plugins/bundle-native` describes the shipped product. Named Profiles apply ordered bundles
over an empty Cordis tree, then user, machine and launch operations. `plugins/profiles`
compiles portable intent and owns metadata/locks. Cordis owns generic combination, contexts,
service realms and tree lifecycle. `plugins/runtime` owns the agent-turn boundary, committed
application projections and coordinated native module/tree publication.

`plugins/ui-profiles` is an optional default settings contribution consuming the neutral
KcodeProfiles management service. Kcode Settings exposes plugin operations for the active
Profile; the full Profile editor is linked into the independent manager. Accepted composition
commands remain host-owned across UI withdrawal. `plugins/profile-management-ui` provides a
host-neutral plugin manager screen used both by the settings contribution and native manager
entry. Alternative roots do not depend on either product UI package.

The native applications link `plugins/profile-recovery-ui` outside the product tree. Android
adds a second launcher activity, and desktop accepts `--plugin-manager`; both render the
independent manager instead of the product root. The entry uses host-owned Profile
metadata/commands and XML text defaults, so product startup or plugin configuration failures
do not remove it. Normal application launches render their product root when ready. Recovery
creates separate repair drafts through the existing neutral client. Historical activation
retains the frozen recipe; editing requires cloning history to a new draft.

Package IDs identify available code. Entry IDs identify configured instances. Multiple
instances may borrow one release export while retaining independent configuration, context,
effects and disposers. Group entries preserve parent contexts and service isolation. Package
availability does not supply an instance or guarantee that a required service exists.
Consumers declare required contracts with `inject` and remain pending when a provider is absent.

Native code is verified through the existing package resolver and deployment graph. External
JAR/APK implementations and private dependencies are loaded independently. Shared SDK and
framework identities are the exact exports in `PluginHostApiPackages`; copying those classes
into private packages is not a compatible substitute. `CurrentPluginApiVersion` and the
generated SDK/framework ABI fingerprint define the deployment boundary. Package variants
declare the host API range they support; current-API packages must match the host fingerprint,
while older APIs in a declared range retain their own locked build fingerprint and require a
publisher compatibility claim.

## Ownership and publication

Provider resources allocate during `apply`, after configuration validation. Each provider
collects independent, idempotent disposers in its owning effect/Fiber. Owned operations cancel
and join before resources close. Borrowed host resources keep their caller-defined lifetime.
Callbacks execute outside registry locks and reject work after withdrawal. Old disposers
must not remove a newer registration with the same ID.

Composition mutations go through `AgentPluginManager`, never direct Loader writes. Native
Profile mode translates manager commands into portable operations and a candidate deployment.
It prevents turn admission while preparing/applying, requires existing turns to finish or
be cancelled, validates every instance before withdrawal, and settles the candidate tree.
Application frames/catalogues are prepared before atomic generation publication. Candidate
code is retained until old tree restoration completes on failure. See the [Profile guide](profiles.md)
for command semantics, durable documents and remaining management/switch work.

Profiles isolate persistent settings/history independently from Cordis service realms.

Native Profile hosts own a `ProfileCommandGateway` outside the replaceable product tree.
An infrastructure bridge in each tree exports the neutral SDK `KcodeProfiles` contract.
Its metadata operations withdraw with the bridge; synchronously accepted commands keep host
ownership through tree replacement. Command handles contain neutral state rather than provider
objects. Starting rejects requests until host binding. RecoveryRequired preserves host metadata
and explicit activation independently of product services. Management UI remains optional.
Machine paths and borrowed callbacks stay outside portable intent. Closing providers and
deleting composition metadata do not erase durable business data.

## UI boundary

The kernel's open `UiSlotKey<T>` contributions live in `plugins/api`. Registry implementations
belong to `ui-contributions`; default typed projection belongs to `default-ui-bridge`.
The optional `plugins/default-ui-api` defines default layout, navigation and presenter contracts.
Feature providers own their optional settings/UI children. Reusable controls, design and
icons belong to the independent `libraries/ui` and receive labels/callbacks from callers.

`ApplicationRenderer` prepares an `ApplicationFrame` through a short-lived services lookup.
Rendering retains the prepared frame rather than resolving live services inside composition.
Each application root declares its own dependencies; the host does not impose default theme,
layout or navigation on an alternative root. No UI root and pending domain consumers are
valid compositions.

Reserved Harness contracts do not imply implemented capabilities. A service must only be
published when its provider implements that contract; placeholder providers are unsupported.


The runtime publishes `KcodeExecution` in the root Context as lifecycle coordination,
independent of product service realms. SDK 75 shares only its admission contract; the
controller remains runtime-private. Agent calls, generation scopes and scheduled dispatch
enter it before executing work. Composition mutation closes admission while checking active
operations, whole-Profile cancellation joins their cleanup, and startup failure/retirement
close stale handles before product resources are released. Autonomous providers must join
this boundary explicitly; it does not create a scheduler or implement reserved Harness APIs.


SDK 76 admits conversation request preparation in the runtime boundary. Commands remain
admitted through their owned asynchronous history operations, while model responses transfer
to the independently owned generation scope. Suspend `startResponse` before allocating IDs
or modifying response state; ordinary sends, setup feedback and regeneration use the same
coordination. Shared chat contracts retain their exported identity; the executor stays private.
