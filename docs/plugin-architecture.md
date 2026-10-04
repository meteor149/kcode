# kcode plugin architecture

kcode is a Kotlin Multiplatform application for Android and Desktop JVM, built with Compose,
Koog, and Cordis. Plugins compose product features. Cordis is a Maven dependency and does
not require a local framework checkout. Browser applications are not a build target;
plugins still provide Web containers, search, and Artifacts within native applications.

This document describes the current API 34 boundaries. Entry points and configuration are
specified by [NativePluginBundle](../plugins/bundle-native/src/commonMain/kotlin/ai/meteor/kcode/plugin/NativePluginBundle.kt)
and the [Gradle module list](../settings.gradle.kts). See [feature ownership](plugin-feature-audit.md)
to locate implementations.

## Module boundaries

| Layer | Ownership and responsibilities |
| --- | --- |
| `plugins/api` | Service keys, events, domain state, persistence contracts, plugin composition protocols, host input, and platform interoperability primitives. Neutral contracts from the former `shared` module are consolidated here. |
| `plugins/default-ui-api` | Optional default application layout, sidebar, pages, navigation, themes, and presentation requests; the kernel does not require root UIs to use them. |
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
| `history`, `artifacts`, `webContainers` | History repositories, artifact repositories, and native Web controllers. |
| `fs`, `shell`, `ubuntuShell`, `web` | File IO, system/Ubuntu execution, and search backends. |
| `goals`, `schedules`, `scheduledTaskNotifications` | Persistent goals, scheduling, and platform notifications. |
| `conversationImageRendering`, `conversationImageSaving`, `conversationExport` | Image rendering, platform saving, and export orchestration. |
| `conversationOverlays` | Optional system conversation overlay controllers. |
| `applicationUi`, `uiContributions` | Generic root UI and opaque, open contribution snapshots. |
| `pluginInventory`, `pluginInstallations`, `loader` | Plugin diagnostics, composition persistence, and dynamic loading. |

See the [SDK](../plugins/api/README.md) for signatures and the
[default UI SDK](../plugins/default-ui-api/README.md) for the default `uiSlots` service definition.
Unimplemented Harness services are listed separately in [reserved APIs](harness-reserved-api.md).
A service definition alone does not imply an available provider.

## Composition, replacement, and commits

`KcodePluginRuntimeConfig.bundle` can supply a custom product list. `KcodePluginProfile`
supports `includeDefaults = false`, ID-based `overrides`, and `disabled`. Duplicate, unknown,
or invalid compositions fail before mounting. Only inventory and the dynamic loader bridge
are fixed bootstrap components; the default agent loop, tools, and UI are replaceable.

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
