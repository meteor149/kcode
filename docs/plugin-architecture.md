# kcode plugin architecture

kcode is a Kotlin Multiplatform application for Android and Desktop JVM, built with Compose,
Koog, and Cordis. Plugins compose product features. Cordis is a Maven dependency and does
not require a local framework checkout. Browser applications are not a build target;
plugins still provide Web containers, search, and Artifacts within native applications.

This document describes the current API 60 boundaries. Entry points and configuration are
specified by [NativePluginBundle](../plugins/bundle-native/src/commonMain/kotlin/ai/meteor/kcode/plugin/NativePluginBundle.kt)
and the [Gradle module list](../settings.gradle.kts). See [feature ownership](plugin-feature-audit.md)
to locate implementations.

## Module boundaries

| Layer | Ownership and responsibilities |
| --- | --- |
| `plugins/api` | Service keys, events, domain state, persistence contracts, plugin composition protocols, host input, and platform interoperability primitives. Neutral contracts from the former `shared` module are consolidated here. |
| `plugins/default-ui-api` | Optional default application layout, sidebar, pages, navigation, themes, and presentation requests; the kernel does not require root UIs to use them. |
| `plugins/ui-contributions` | Neutral contribution registry implementation, independently usable by alternative roots. |
| `plugins/default-ui-bridge` | Optional typed default UI service and projection; requires the neutral registry and contains no pages. |
| `libraries/ui` | Independent reusable components, design contracts, icons, and resources. Callers supply text, data, slots, and callbacks; components do not query services or navigate. |
| Feature plugins | Product implementations, providers, model tool consumers, UI contributions, and policy. Consumers depend on contracts and do not import concrete providers. |
| `plugins/bundle-native` | Default product list with profile overrides; default implementations have no startup privileges. |
| `plugins/runtime` | Fiber mounting, composition management, loading/unloading, and snapshot commits; delegates default product entry points to the bundle. |
| `plugins/platform-android`, `platform-desktop` | Platform assembly, trusted dynamic Loaders, and system adapters installed in the host. |
| `apps` | Android Activity / Desktop Window, application lifecycle, and forwarding of platform events. |

Generic file, Shell, and Web execution contracts remain in the SDK. Room DAOs, MMKV encoding,
PRoot installation, Chromium/WebView, dictionaries, themes, and pages belong to their
respective plugins. The host adapter declares Android services, permissions, and
FileProviders that must reside in the host manifest.

API 47 shares Koog framework namespaces and explicit client protocol identities rather
than the entire `ai.koog` namespace. Vendor clients, the OpenAI-compatible base, and
AWS/Smithy belong to model adapter archives. Each vendor release carries its necessary
private dependency closure; upstream Bedrock model/serialization code requires Anthropic.
The SDK no longer supplies concrete vendor implementations through its Gradle API.

## Service definitions, providers, and consumers

Service definitions declare stable `ServiceKey` values and data contracts. Providers supply
implementations when mounted. Consumers declare dependencies with `inject` and use services.
Missing dependencies leave consumers Pending; restoring them rebinds consumers. The composition
layer may select concrete implementations. Ordinary consumers must not depend on providers.

| Service keys | Contracts and main uses |
| --- | --- |
| `tools`, `systemPrompt`, `continuations` | Revocable registries for tools, prompt sections, and continuation policies. |
| `llm`, `modelSettings` | Model adapters/catalogs, configuration resolution, and update policy. |
| `agents`, `generation`, `conversationExecution`, `sessions` | Chat services, generation scheduling, conversation execution, and history projections. |
| `interaction`, `toolApprovals` | Permission modes and tool approvals. |
| `skills`, `skillWorkspace`, `subagents` | Skill catalogs/execution environments and subagent coordinator factories. |
| `settings`, `settingsCommands`, `searchSettings`, `localization` | Persistent settings, settings commands, search configuration, and localization. |
| `shellMode` | API 45 execution-identity policy; settings and caller callbacks provide it independently of native Shell implementations. Withdrawal suspends native consumers and cancels their work. |
| `history`, `artifacts`, `webContainers` | History repositories, artifact repositories, and native Web controllers. |
| `fs`, `shell`, `ubuntuShell`, `web` | File IO, system/Ubuntu execution, and search backends. |
| `goals`, `schedules`, `scheduledTaskNotifications` | Persistent goals, scheduling, and platform notifications. |
| `conversationImageRendering`, `conversationImageSaving`, `conversationExport` | Image rendering, platform saving, and export orchestration. |
| `conversationOverlays` | Optional system conversation overlay controllers. |
| `applicationUi`, `uiContributions` | Generic root UI and opaque, open contribution snapshots. |
| `pluginInventory`, `pluginInstallations`, `pluginPackages`, `loader` | Plugin diagnostics, composition persistence, verified cross-platform package resolution, and dynamic loading. |

See the [SDK](../plugins/api/README.md) for signatures and the
[default UI SDK](../plugins/default-ui-api/README.md) for the default `uiSlots` service definition.
Unimplemented Harness services are listed separately in [reserved APIs](harness-reserved-api.md).
A service definition alone does not imply an available provider.

## Composition, replacement, and commits

`KcodePluginRuntimeConfig.bundle` can supply a custom product list. `KcodePluginProfile`
supports `includeDefaults = false`, ID-based `overrides`, and `disabled`. Duplicate, unknown,
or invalid compositions fail before mounting. Only inventory and the dynamic loader bridge
are fixed bootstrap components; the default agent loop, tools, and UI are replaceable.

A supplied composition store installs its managed provider independently of product defaults.
Native hosts offer no default feature archives when `includeDefaults = false`. Retired bundled
identities remain installed while surviving external packages require them, including transitive
dependencies. Their replacement aggregate starts disabled to avoid conflicting providers; package
dependencies and verified artifact identities are never silently rewritten.

API 46 makes subagent capability optional in turn tool contexts and Artifact capability optional
in default UI projections. The agent resolves skills, subagents, and overlays per turn; missing
subagents retract their tools rather than suspending ordinary conversation. Artifact withdrawal
retracts its page/navigation while the default root and generic settings page remain available.

Optional services use private reactive child plugins that declare their own `inject`
dependencies. They publish revocable bindings to the owner, clear only their own binding
on withdrawal, and do not suspend the owner when absent. Reading an undeclared optional
service directly from the owner's Cordis context is invalid, even if the service exists.

Scheduled dispatch runs within its plugin lifetime, without application rendering or UI
effect registrations. Each due task reads committed settings and publishes through the
shared session data plane. Localization, Goal sessions and notifications are optional;
notification failure does not invalidate a published result. Withdrawal joins the due-task
runner and active generations and discards unpublished standalone conversations.

`pluginManager` is the unified path for loading/unloading built-in and external plugins.
`setEnabled` preserves configuration; built-in `uninstall` means disabling. `installed()`
returns external packages, while `diagnostics()` provides complete status. After an external
package replaces a built-in implementation, uninstalling it does not automatically activate
the original implementation. Its original configuration can be explicitly re-enabled.

Use `replacePlugin(KcodePluginMount)` for in-process replacement and
`install/replace(DynamicPluginSpec)` for external packages. Reject configuration errors before
revoking the old Fiber. Restore the committed composition if candidate application, manifest
saving, or snapshot preparation fails. Recovery may recreate providers and does not promise
to migrate the old instance's in-memory state. Providers must define persistence/recovery
contracts for business state that needs to survive replacement.

Loading/unloading is serialized. Tree changes are rejected during active agent turns.
Complete or cancel a turn before changing tools, models, or policy. Runtime close cancels
and awaits active work; subsequent calls are rejected. The chat facade resolves the current
`agents` service for every new turn.

## Registration and resource lifecycles

Tool, prompt, policy, UI, and listener registrations return `Disposable` values collected
by their Fiber's effects. Unloading cancels and awaits in-flight contribution calls before
releasing clients, windows, databases, threads, processes, and deployment files. Failed
initialization must release partial allocations. Cancellation before the commit point
requires rollback; cancellation afterward preserves published facts.

`PluginOperationOwner` protects provider calls, awaits cleanup, and aggregates cleanup
failures other than cancellation. A call must not close its own owner or mutate its runtime,
to avoid waiting on itself. Plugins close resources created by mount factories. They must
not close explicitly borrowed repositories, flows, or host input. Stale handles retained
after revocation must reject further calls.

Model adapter creation performs only bounded allocation. Allocations completed during
revocation are discarded and released. Requests and stream collection on returned clients
also belong to the adapter's ownership. Native Shell revocation must confirm that managed
process groups have exited; cross-UID EPERM does not establish process exit. See
[plugin development](plugin-development.md) for additional resource and package identity rules.

## UI boundaries

The generic root entry point is `ApplicationRenderer.snapshot(ApplicationServices): ApplicationFrame?`.
Root plugins resolve their own dependencies. The host neither forces a default theme wrapper
nor requires default business services such as settings/history/artifacts. `ApplicationServices`
queries are valid only during frame preparation. Retain resolved capabilities, not the query object.

Core `KcodeUiContributions` accepts `UiSlotKey<T>` values with an owning type contract.
`snapshot()` prepares all projections; `snapshot(key)` invokes only the selected provider.
Providers execute outside the registry lock. Revocation cancels and awaits projections being
prepared. The owning contract guarantees type consistency for the same ID.

The default UI uses `KcodeUiSlots` and `ApplicationUiSlots`. Single slots have one registration;
settings, message, tool, navigation, and effect contributions are sorted by `order` and `id`.
Duplicate registrations fail. `resolve(key)` reads default or custom slots by contract ID
during frame preparation. Repeated old disposers do not remove newer registrations, even
when the newer registration reuses the same renderer object.

UI plugins implement default pages, message presentation, theme parameters, and layout.
`libraries/ui` provides the parameterized `KcodeTheme` and basic design contracts. Default
palettes, typography, and configuration come from the theme provider. Pages do not directly
create conversation execution, generation, or persistence services. Native overlays receive
opaque contribution snapshots; default adapters select default projections afterward.

## Events and durable state

| Event | Mode and use |
| --- | --- |
| `agent/pre-step` | Waterfall: rewrite input or short-circuit. |
| `tools/pre-execute` | Waterfall: process requests before permission checks and execution. |
| `tools/post-execute` | Waterfall: process executed results. |
| `agent/turn-started`, `agent/turn-finished` | Awaited parallel: observe turn start, success, or failure. |

Waterfall listeners call `next()` to delegate downstream; short-circuiting is an explicit
decision. Live events are not a durable session log. `ConversationHistoryRepository`
currently persists history; the full Harness session event log remains a reserved contract.

## Platforms and unimplemented capabilities

Default product implementations execute from independent packages. Native hosts retain SDK
contracts, composition/installation infrastructure and SDK-only input adapters. The
distribution catalog in `distribution/packager` defines the current release boundaries.
Artifact, Web Search, Goal, Schedule, Subagent, Localization, Markdown, Conversation Export and WebContainer include their domain providers and consumers within
one feature release, including their optional settings/presentation. The neutral UI contribution registry
and default UI projection have separate infrastructure releases; concrete pages consume
them. Other feature boundary changes are tracked in the [feature audit](plugin-feature-audit.md).
The [cross-platform package format](plugin-package-format.md) supplies
separate `.kplugin` releases with desktop and/or Android variants, verified import and
transactional dependency-set installation and bundled startup/upgrade policy;
supported default implementations are excluded from production host implementation classpaths.

The SDK preserves host-shared contract/resource identity; private ClassLoaders load product
implementations. External packages cannot fall back to host-private implementations. Android
remote deployment uses verified package and resource graphs. Privileged bridges check actual
UIDs and package digests. Successful local calls do not replace independent Binder/permission
environment verification.

The default bundle does not yet provide Harness jobs, independent subprocess/PTY services,
a full session log, or filesystem observation/version guards. The current loading protocol
also does not guarantee download/publisher verification, cross-service ABI negotiation, or
arbitrary state migration. Actual Shizuku authorization/connection and UID-0 environments
require separate verification; see the [verification guide](verification.md).

The design reference is pinned to the commit stated in the
[Harness document](deepseek-harness-plugin-spec.md).

## Feature-owned settings (API 45)

Settings pages are generic contribution hosts. Their request contains only stored settings,
persistence feedback, save/dismiss callbacks and the section list. Each feature archive owns
its form, state preparation and validation. Localization, model settings, search settings
and execution settings mount optional settings children under the owning provider. UI slot
withdrawal suspends children without withdrawing the feature policy; feature withdrawal
removes its section without removing the page. Retained section callbacks reject saves.

Settings commands similarly dispatch through feature registrations. The dispatcher depends
only on storage, verifies all supplied fields before execution and holds each participating
contribution's operation owner through save. Missing search cannot block model updates.

## Search feature ownership

`feature.web-search` is one install/enable boundary implemented by `plugins/web-search`.
Its child Fibers own HTTP transport, the model tool, settings policy, validation and UI.
The settings page consumes registered sections only. Search withdrawal retracts its
section and command handlers, cancels searches and closes the client; re-enabling restores
those contributions using persisted credentials. No search-settings-only release is shipped.

API 48 gives conversation decorations generic header and composer anchors plus optional
borrowed haze context. Subagent filtering, status cards and details belong to its feature
package; the default page only prepares, places and measures generic contributions. Its
optional UI child withdraws with UI/localization services without suspending coordination
or tools. The shared default UI namespace carries the contract, not feature implementations.

## Generic configuration persistence (API 59)

`StoredAppSettings` carries feature namespace JSON and opaque historical JSON only.
Persistence has no model/search/language/mode fields or product defaults. MMKV and DataStore
read v2 snapshots first, migrate v1 snapshots preserving unknown roots, then read historical
scalars through a bounded key-name translation table. Current writes publish a single v2
snapshot without rewriting or deleting old/unknown native preferences. Feature providers
own absent/empty value interpretation and namespace schemas, including legacy route/default
semantics. Generic persistence must not interpret credentials or select feature providers.

## Feature-owned settings mutations (API 60)

The SDK settings service exposes a generic namespace-validation registry and a validated
mutation store. Each feature supplies its own schema and selection rules in a headless
child that does not require the default UI or settings commands. The default root uses
this mutation store, so it knows no feature namespace identities. Validation occurs after
draft differences merge with the current transaction snapshot; all affected owners remain
admitted until the durable save returns. Withdrawals cancel and join those operations.
Disabled and unknown namespaces remain persisted, while editing an unowned namespace or
historical migration values is rejected. Alternative command consumers continue using
feature-owned transforms and the same transaction-capable storage boundary.
