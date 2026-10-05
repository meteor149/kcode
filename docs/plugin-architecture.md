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
KcodeProfiles management service. It owns presentation sessions and resource defaults;
accepted composition commands remain host-owned across withdrawal. Alternative roots and
independent recovery entry points do not depend on this package.

Package IDs identify available code. Entry IDs identify configured instances. Multiple
instances may borrow one release export while retaining independent configuration, context,
effects and disposers. Group entries preserve parent contexts and service isolation. Package
availability does not supply an instance or guarantee that a required service exists.
Consumers declare required contracts with `inject` and remain pending when a provider is absent.

Native code is verified through the existing package resolver and deployment graph. External
JAR/APK implementations and private dependencies are loaded independently. Shared SDK and
framework identities are the exact exports in `PluginHostApiPackages`; copying those classes
into private packages is not a compatible substitute. `CurrentPluginApiVersion` and the
generated SDK/framework ABI fingerprint must both match the deployment boundary.

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
