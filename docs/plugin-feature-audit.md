# Feature ownership and verification entry points

This table describes source ownership for the current API 60. It does not repeat historical
test counts or migration conclusions. Neutral contracts live in [plugins/api](../plugins/api/README.md),
default UI protocols in [default-ui-api](../plugins/default-ui-api/README.md), and reusable
controls in [libraries/ui](../libraries/ui/README.md). See [bundle-native](../plugins/bundle-native/README.md)
for the default composition.

## Product features

Module names are relative to `plugins/`. Test names identify entry points; consult the
[verification guide](verification.md) for their actual scope and execution methods.

| Feature | Implementations and contributions | Main verification entry points |
| --- | --- | --- |
| Model adaptation, streaming chat, tool policy, and continuation | `llm-service` owns the independently packaged registry; `llm` owns the independently packaged adapters; `agent-loop`, `tools`, `system-prompt`, `continuations` | KoogClientLifecycleTest, OwnedAgentChatServiceTest, FormalModelAdapterPrivateLoadingTest |
| Model configuration resolution and connection policy | `model-settings`, including its catalog-driven settings form | ModelSettingsPolicyTest, ModelSettingsPrivateLoadingTest |
| Language, dictionaries, formatting, and language settings | `localization`; one `feature.localization` release owns the dictionary and optional UI projection | LocalizationPrivateLoadingTest, LocalizationFeatureLifecycleTest, AndroidLocalizationPrivateLoadingTest |
| Session projections, send/stop/regenerate, and background generation | `session-history`, `conversation-execution`, `message-codec`; `native-notifications` provides Android foreground policy | GenerationCompositionTest, MessageCodecPrivateLoadingTest, ConversationOverlayLifecycleTest |
| History and settings storage | `history-repository`, `settings-repository` | HistoryResourceCompositionTest, SettingsResourceCompositionTest, AndroidSettingsStorageTest |
| ADB settings commands and UI settings commits | `settings-commands`, `application`; the host decodes Intents | SettingsCommandsCompositionTest, ApplicationSettingsSessionTest, AdbSettingsPluginTest |
| File IO, editing, and media reading | `native-filesystem` provider, `filesystem` consumer; `capability-providers` adapts contracts | NativeWorkspaceCompositionTest, AndroidNativeWorkspaceTest, CapabilityCompositionTest |
| System Shell, Ubuntu, and desktop execution | `native-execution` supplies independently packaged system/desktop Shell and ARM64 Ubuntu; `execution-settings` provides Android mode policy, `shell` consumes executors | NativeShellCompositionTest, AndroidSettingsShellTest, native-execution instrumentation |
| Permission modes and approvals | Independently packaged `interaction-settings` default and callback-configured `interaction` alternatives consume the SDK approval service supplied by `native-tool-approvals`; consumed by the agent loop | SettingsInteractionCompositionTest, StoredToolPermissionSettingsPolicyTest, NativeToolApprovalCompositionTest, AndroidNativeToolApprovalTest |
| Skill discovery, reading, and request instructions | `skills`, `skill-tools`, `system-prompt`, `agent-loop` | SkillRuntimeTest, SkillCatalogRendererTest |
| Subagent dispatch, messaging, waiting, interruption, and continuation | `subagents`; one `feature.subagents` release owns coordinator, tools, continuation and optional status/detail presentation | OwnedSubagentFactoryTest, SubagentToolsTest, CapabilityCompositionTest |
| Goals, commands, automatic continuation, and Goal presentation | `goal`; one `feature.goal` release owns all six internal contributions | GoalCommandHandlerTest, GoalSchedulePrivateLoadingTest, GoalUiLifecycleTest |
| One-time/recurring scheduling and notifications | `schedule` owns one `feature.schedule` release; `native-notifications` remains reusable infrastructure | NotificationCompositionTest, AndroidNativeNotificationsTest, GoalSchedulePrivateLoadingTest |
| Web search | `web-search` owns HTTP transport, the tool, configuration policy, commands and settings UI in one package | WebSearchToolTest, FormalRuntimeEntryPrivateLoadingTest |
| Web Artifact repositories, saving, and opening | `artifact-repository` provides replaceable storage; `artifact-tools` owns `feature.artifacts` tools, page and navigation; `web-container` runs artifacts | ArtifactProviderCompositionTest, AndroidArtifactProviderTest |
| Web containers, debugging, and native Web API bridges | `web-container`; the host declares platform manifest adapters | NativeWebContainerCompositionTest, AndroidNativeWebContainerTest, WebBackgroundContainerOverlayTest |
| Root UI, layout, sidebar, pages, messages, and navigation | `application`, `ui-pages`; protocols in `default-ui-api` | PrivateApplicationRenderingTest, FormalUiContributionPrivateLoadingTest, DefaultUiSlotsTest |
| Neutral UI registry and optional default projection | `ui-contributions`, `default-ui-bridge`; no concrete pages in either provider | UiBridgeOwnershipTest, FormalUiContributionPrivateLoadingTest, BundledPluginDistributionTest |
| Themes, settings sections, and Markdown | `ui-pages`, feature-owned settings contributions; `feature.markdown` owns formatting and its optional UI projection | FormalUiContributionPrivateLoadingTest, MarkdownPrivateLoadingTest, MarkdownFeatureLifecycleTest |
| Conversation image export, rendering, saving and presentation | `conversation-export`; one `feature.conversation-export` release owns all three services, optional controls, notices and operation state | ConversationExportLifecycleTest, ChatExportStateTest, ExportUiPrivateLifecycleTest, ImageSavingCompositionTest, AndroidNativeImageSavingTest |
| System conversation overlays and standalone conversation presentation | `conversation-overlay`; `ui-pages` provides transcript/standalone slots | ConversationOverlayCompositionTest, AndroidConversationOverlayProviderTest |
| Plugin installation, enabling, replacement, and recovery | `inventory`, `installation-store`, `runtime`, platform Loaders | KcodePluginRuntimeTest, AndroidPluginCompositionTest, PluginCodeOriginTest |
| Cross-platform archives and bundled release upgrades | `package-provider`, `runtime`; the root Gradle build and native host resources | PluginPackageIntegrationTest, AndroidPluginPackageIntegrationTest, BundledPluginDistributionTest (desktop and Android app) |

## Host and composition boundaries

`apps` retains Activity/Window, process lifecycle, broadcast decoding, and generic platform
forwarding. SDK platform input, Binder/PFD, and native MMKV/SQLite leases preserve system
resources and cross-loader identity. These primitives do not own product dictionaries,
themes, pages, or storage policy. `test-support` is for tests only.

Default features run as built-in plugin compositions and can be overridden by external
JARs/APKs. Replaceability does not imply that the first application installation automatically
creates or installs every external package. Verify implementation identity, call revocation,
resource release, and state recovery separately.

## Dependency and packaging remediation plan

### Current whole-repository review (2026-10-05)

This review covers the 50 `plugins/*` Gradle modules, their production project
dependencies, the native composition, and the distribution catalog. Test-only
dependencies are excluded from the production graph. The graph has 114 plugin-to-plugin dependency declarations plus 17 library declarations (131 declared
production project edges), including composition `compileOnly` edges, and no plugin cycles. The
catalog declares 67 `BundledProvider` entries with unique IDs, plus the separately
packaged message codec (68 logical releases). Module, service, internal child
Fiber, and installable release boundaries are different concepts.

The following findings record current source and verification status, including the earlier
remediation stages below have passed their recorded checks:

| Priority | Remaining problem and evidence | Required boundary |
| --- | --- | --- |
| Resolved | Generation, History, Sessions and Localization are optional in the default root (API 49–51). Core pages and sidebar consume prepared projections; the root prefers borrowed localization and uses revocable page/feature-owned English defaults when unavailable. Actual private-JAR tests render core UI and settings forms, commit configuration and recover providers. | Root independence is verified for these four feature capabilities; settings storage and actual default UI/theme contributions remain required infrastructure. |
| Resolved in source; regression in progress | API 60 routes root UI writes through generic namespace mutation validation. Each settings feature registers its rules; affected registration lifetimes cover validation and durable commit in the shared transaction. Missing owners reject namespace edits while stored data survives. | The settings root depends on neutral settings infrastructure; schema ownership and selection rules remain in feature packages. |
| Resolved | All settings-named factory/native entry adapters now inject `KcodeShellMode` instead of parsing storage. System/Ubuntu tests cover namespace selection and caller-policy replacement/withdrawal without settings storage. | Consumers depend only on the mode contract; compositions select the settings-backed or caller policy explicitly. |
| Resolved | API 59 removes fixed model/search/language/execution/permission fields and storage product defaults. `StoredAppSettings` carries opaque namespaces and raw `legacyValues`; feature owners interpret historical values only when their namespace is absent. MMKV/DataStore commit one v2 document and preserve v1 records/scalar keys, unknown roots and disabled namespaces. | Complete configuration schemas/defaults belong to their features. Generic storage migrates data without selecting providers; current UI durable validation remains the separate P1 item. |
| Resolved | API 53 moves export menus, notices, state, operations and default texts to the export feature. Chat and root requests no longer receive exporters; presenters prepare generic header actions and above-composer projections once. Private-JAR rendering verifies withdrawal/recovery and that compact New Chat remains functional without export. | Export owns its optional presentation; headless calls remain usable without UI slots. Feature and page owners cancel and join operations; borrowed exporters are not closed by presentation. |

The full module coverage is grouped below. “Retain” describes a justified boundary;
it does not claim that every possible lifecycle interleaving was executed in this review.

| Modules | Boundary assessment |
| --- | --- |
| `api`, `default-ui-api`, `test-support` | Retain neutral service contracts, optional default UI protocols and test-only fixtures. API 59 removes feature-specific fixed DTOs from the SDK. |
| `ui-contributions`, `default-ui-bridge` | Retain separate neutral registry and default projection; neither owns concrete pages. |
| `application`, `ui-pages` | Retain replaceable default root/layout/page contributions; optional conversation/localization dependencies and export presentation ownership are corrected. API 60 adds durable feature settings validation; regression evidence is recorded below. |
| `web-search`, `model-settings`, `localization`, `execution-settings`, `interaction-settings`, `settings-commands`, `settings-repository` | Search policy/tool/settings are one feature release; model and language sections belong to their features. Storage and generic command infrastructure remain independently replaceable. API 59 removes fixed persisted DTOs and storage defaults; API 60 adds feature-owned UI mutation validation; final regression is in progress. API 52 serializes UI and command writes through the same transaction. Execution settings are shared across compatible Shell backends, rather than duplicated per backend. |
| `goal`, `subagents`, `schedule`, `markdown`, `conversation-export`, `web-container`, `artifact-tools` | Retain aggregate feature releases with optional internal UI consumers. Artifact, WebContainer, Subagent and Export presentation belong to their features. Schedule dispatch is headless. |
| `artifact-repository`, `history-repository`, `session-history`, `message-codec`, `conversation-execution` | Retain replaceable persistence, shared session projections, encoding and execution contracts. The default root now keeps settings independent of conversation prerequisites. |
| `agent-loop`, `llm`, `llm-service`, `tools`, `system-prompt`, `continuations` | Retain provider registries, replaceable model adapters and independent execution/policy contributions. Optional Skills/Subagent/Overlay bindings use reactive children. Multiple model releases in one Gradle module are intentional; vendor implementations remain privately packaged. |
| `native-filesystem`, `capability-providers`, `filesystem`, `native-execution`, `shell`, `skills`, `skill-tools` | Retain backend/consumer boundaries: a tool can consume an alternative provider. `native-filesystem` deliberately embeds its private `capability-providers` adapter; it is not a consumer importing a replaceable backend. Goal/Subagent and Filesystem/native provider dependencies seen in tests are not production dependencies. |
| `interaction`, `native-tool-approvals`, `native-notifications`, `conversation-overlay` | Retain replaceable approval, platform notifications/foreground behavior and optional overlay providers. Independent entry points in the same module need not be one atomic business feature. |
| `bundle-native`, `runtime`, `inventory`, `installation-store`, `package-provider`, `platform-android`, `platform-desktop` | Retain composition, lifecycle, diagnostics, persistence, verified package resolution and native loader boundaries. Concrete entry references in the composition layer are intentional; ordinary consumers use SDK contracts. |

`search-settings` is not a separate current product release: `feature.web-search`
owns its policy, HTTP backend, tools, command transforms and optional settings section.
Legacy entry names remain for verified archive migration and explicit alternative
compositions; their existence is not evidence of duplicate production releases.

This pass is a static architecture review. It does not rerun or extend the historical
test/device evidence below, and it makes no new Android device or paid API claims.

The follow-up source audit reconfirmed the 50 modules and 112 production dependency
declarations, excluding test configurations and packaging-task project references.
There are no dependency cycles or duplicate IDs among the 67 catalog entries.
Outside composition, native loaders and the deliberately embedded filesystem adapter,
plugin production project dependencies target the neutral SDK or default UI SDK.
`WebSearchFeaturePlugin` mounts search policy, backend and tools together; the policy
owns the optional settings section. The unresolved settings transaction, fixed settings
schema and export presentation findings above were checked against current source,
not inferred from old module names. No additional implementation changes or test runs
are claimed by this follow-up audit.

The acceptance boundary is the complete feature, including its commands, tools, settings,
and optional presentation. Internal Cordis children remain independently reactive; they do
not automatically become separate installable products. Reusable infrastructure and genuinely
replaceable backend/model implementations retain their own deployment boundaries.

Implementation and verification stages:

1. Make composition infrastructure independent of the default product profile. Verify native
   `includeDefaults = false`, explicit package imports, restart persistence, and alternate roots.
2. Migrate retired package identities without breaking retained external dependency graphs.
   Preserve verified artifacts and former feature peers required by surviving packages, suppress conflicting aggregate
   defaults, and verify successful recovery and failed-commit rollback using real packages.
3. Remove optional skills, subagents, overlays, and artifacts from core agent/root requirements.
   Verify ordinary conversation and settings rendering during withdrawal and recovery, owned
   operation cancellation, and retained callback rejection.
4. Move scheduled execution into a non-UI lifecycle. Verify dispatch without default UI,
   Markdown, or notifications, plus replacement, cancellation, and task persistence.
5. Tie shared execution settings to actual compatible execution capabilities. Verify system
   and Ubuntu backend combinations, complete withdrawal, recovery, and stale settings callbacks.
6. Aggregate atomic Goal, Schedule, Subagent, export, Markdown, localization, and WebContainer
   products. Separate neutral UI registries/default projection from concrete pages, and move
   feature presentation into its owning feature package. Verify manifests, dependency closure,
   migration, and real desktop JAR/Android APK implementation and resource identities.
7. Narrow SDK sharing to public framework contracts and introduce namespaced plugin settings
   values through the common command/persistence boundary. Review ABI/export changes and test
   unknown plugin configuration, durable round trips, withdrawal, and transaction rollback.
8. Update architecture, module entry points, both READMEs, and verification evidence. Run SDK,
   default UI, runtime/composition suites, build both hosts, and verify the revised distribution.

Stages are tracked against implemented behavior and verification evidence; green tests that
merely reproduce the old coupling do not establish completion.

Initial implementation evidence (API 46): `PluginPackageIntegrationTest` exercises explicit
bundled installation without defaults/manual installation provider, restart enable state, and
transitive alias retention/cleanup using real archives. `KcodePluginRuntimeTest` covers optional
provider withdrawal and subagent tool recovery. `KoogClientLifecycleTest` covers absent/present
coordinators on successive turns and cleanup on failure. `PrivateApplicationRenderingTest`
renders the real default UI while withdrawing/restoring Artifact page/navigation. These targeted
suites passed with the local Cordis source composite; they do not establish completion of
scheduled execution, feature aggregation, SDK dependency narrowing, or namespaced settings.
The SDK/default UI/subagent desktop tests, desktop host compilation, Android host assembly,
and Android instrumentation-source compilation also passed for these changes. Instrumentation
compilation is not device execution. Native host behavior beyond these targeted paths remains
part of the final verification stage.

`SettingsShellModeOwnershipTest` now verifies actual system/Ubuntu capability withdrawal:
either backend retains one shared settings contribution, and the last withdrawal removes it.
The neutral registry and default projection implementations have moved out of `ui-pages` into
`ui-contributions` and `default-ui-bridge`. `UiBridgeOwnershipTest` confirms that alternative
root projections remain registered when the default bridge withdraws. Goal now has one
`feature.goal` release owning its six internal providers/consumers, including presentation.
Subagent's factory, tools and continuation now share the `feature.subagents` release and
the `subagents` module. Localization's dictionary, language settings and default UI
projection share one `feature.localization` release. Markdown formatting and its default
projection share one `feature.markdown` release. Rendering, saving and export orchestration
share the `feature.conversation-export` release. Schedule now owns one `feature.schedule` release for its provider, tools and headless dispatcher.
WebContainer's overlay implementation and lifecycle tests
have moved from concrete pages into its feature module; the default page factory no longer
mounts it. Its controller, tools and overlay share the `feature.web-container` release.
Moving the remaining feature-specific UI out of concrete pages is also pending.
API 47 removes vendor clients and AWS/Smithy from SDK sharing and packages each adapter's
required private dependency closure. Namespaced plugin settings storage/commands and remaining feature UI ownership are pending,
followed by final broad/device verification.

After this split, the complete desktop platform suite passed (127 tests), along with the
execution-settings and default bridge tests. The production-classpath subprocess loaded the
78 desktop-compatible archives with provider implementations absent from the host classpath.
Both hosts built, and both Android platform/app instrumentation sources compiled. This is
desktop execution and Android compilation evidence, not new device execution evidence.

After Goal aggregation, the complete desktop platform suite passed (129 tests), including
two real-archive migration cases: disabled-choice/failed-commit preservation, and retention
of the entire old Goal group for an external dependent. The latter verifies actual old Goal
implementations remain functional, then cleans up the group after dependent removal and
enables the aggregate. Goal's desktop suite also passed. The production subprocess now
loads 73 desktop-compatible archives. These runs used the local Cordis source composite.
Desktop host compilation, Android host assembly, and both Android platform/app
instrumentation-source compilation passed after this aggregation. No new Android device
execution is claimed.

Subagent aggregation passed the complete desktop platform suite (129 tests), its desktop
suite (12 tests), and both host builds and Android instrumentation-source compilation.
`SubagentFeatureLifecycleTest` verifies late registry arrival, registry withdrawal/recovery,
and atomic withdrawal of the coordinator, tools and continuation. Real private-loading
tests now replace the aggregate with configured capacity, verify failed replacement keeps
the old factory, and hold child cleanup to prove feature withdrawal joins it. The package
migration test exercises disabled-choice/failed-commit preservation for both Goal and
Subagent aliases. The production subprocess loads 71 desktop-compatible archives. These
runs used the local Cordis source composite; no new Android device execution is claimed.

Localization aggregation passed the complete desktop platform suite (129 tests), its
desktop suite (4 tests), both host builds, and both Android instrumentation-source
compilations. `LocalizationFeatureLifecycleTest` verifies headless translation, late UI
arrival, UI withdrawal/recovery without retiring the dictionary, and withdrawal of both
language settings and localization projection while an independent slot remains available.
Private-loading tests now replace the aggregate, preserving custom dictionaries, strict
stale-handle rejection, UI binding and restart/re-enable behavior. Migration rollback
coverage includes both old localization IDs. The production subprocess loads 70
desktop-compatible archives. These runs used the local Cordis source composite; Android
device behavior remains part of the final verification stage.

Markdown aggregation passed the complete desktop platform suite (129 tests), its desktop
suite (2 tests), both host builds, and both Android instrumentation-source compilations.
`MarkdownFeatureLifecycleTest` verifies formatting before UI arrival, UI withdrawal and
recovery without retiring formatting, and feature withdrawal that removes its projection
and rejects retained formatting handles while independent slots remain available. Actual
private JAR/APK test entry points now replace the whole feature; desktop execution verifies
formatting/projection identity, dependent export withdrawal, restart and recovery. Migration
rollback coverage includes both old Markdown IDs. The production subprocess loads 69
desktop-compatible archives. These runs used the local Cordis source composite; APK source
compilation is not new Android device execution evidence.

Conversation Export aggregation passed the complete desktop platform suite (129 tests),
its desktop suite (9 tests), both host builds, and both Android instrumentation-source
compilations. `ImageSavingCompositionTest` now disables the whole feature, verifies stale
saver/exporter rejection, failed/cancelled allocation cleanup, and that withdrawal joins
held saving cleanup before resource release. Formal private-loading and host-input tests
replace the aggregate, checking actual implementation identity and persisted native lease
recovery. Markdown withdrawal keeps the aggregate mounted while withdrawing its export
service; dependent scheduling still becomes Pending. The Android custom-renderer fixture
uses an explicit alternative test composition, rather than restoring split production
defaults. Migration rollback coverage includes all three old export IDs. The production
subprocess loads 67 desktop-compatible archives. These runs used the local Cordis source
composite; no new Android device execution is claimed.

WebContainer presentation ownership migration passed its desktop suite (16 tests), the
remaining default page suite (10 tests), and the complete desktop platform suite (129
tests), including real private JAR loading. Both hosts built and both Android
instrumentation-source compilations passed. Overlay/action implementation and owned
polling/button lifecycle tests now compile in `web-container`; `ui-pages` no longer
references or mounts them. The overlay release now uses the feature module's artifact.
This establishes implementation ownership, not completion of WebContainer release
aggregation: provider, tools and overlay still have three release IDs. Runs used the
local Cordis source composite; no new Android device execution is claimed.

The following WebContainer aggregation passed its desktop suite (16 tests), the complete
desktop platform suite (129 tests), both host builds and both Android instrumentation-source
compilations. The production subprocess then passed again with the new feature classes
excluded from the host implementation classpath, loading 65 desktop-compatible archives.
`NativeWebContainerCompositionTest` imports the real aggregate and verifies independent
Chromium/server generations and cleanup/recovery. `WebHostProjectionTest` and Compose
composition tests verify aggregate withdrawal removes tools and overlay, rejects retained
controllers, waits for held cleanup and preserves the unrelated chat slot. Native desktop
workspace configuration and Android package assets now use the aggregate identity. Alias
rollback coverage includes all three former WebContainer IDs. These runs used the local
Cordis source composite; APK source compilation does not establish new device execution.

API 47 vendor isolation passed SDK desktop tests (41), LLM desktop tests (9), and the
complete desktop platform suite (130), including the production subprocess loading all
65 desktop-compatible archives without vendor/AWS implementations on the host classpath.
`FormalModelAdapterPrivateLoadingTest` constructs all 11 independently loaded clients,
checks that their SDK-owned facades wrap private implementations sharing the host
`LLMClient` protocol, and exercises withdrawal, replacement rollback and recovery. Archive
checks retain only necessary vendor dependencies; Bedrock legitimately carries the
Anthropic model/serializer dependency used upstream. Desktop publish tasks now declare
their exact artifact input instead of the entire output directory.

Both host builds and both Android instrumentation-source compilations passed. The ten
provider-specific Android APKs were additionally inspected at their DEX defined-class
boundary: each contains its selected client, omits unrelated vendor clients and does not
redefine `LLMClient`. These runs used the local Cordis source composite. APK packaging,
DEX inspection and source compilation do not establish new device or paid API evidence.

The Schedule prerequisite session-data change passed session-history desktop tests (14),
the complete desktop platform suite (130), both host builds and both Android
instrumentation-source compilations. A session provider now owns one shared loaded data
plane, mutation mutex and ID sequence; consumer leases retain independent selection,
scopes and exact generation-job ownership. Existing and newly opened leases see published
standalone results directly, without a presentation bridge. Closing a UI lease does not
cancel a background lease's job, and held cancellation cleanup is joined. Delayed cleanup
from an older lease cannot clear a newer job slot; inactive projection entries are pruned.
Provider withdrawal attempts all lease releases even if one fails.

`ConversationPolicyPrivateLoadingTest` then passed an additional actual-JAR check: two
leases from the privately loaded provider publish and observe the same standalone result
while retaining the shared SDK/private implementation identities. The corresponding real
APK test source was updated and compiled. This is the data-plane prerequisite, not
completion of headless Schedule dispatch: `ScheduleDispatchPlugin` still uses its current
UI effect and must be replaced in the following stage. These runs used the local Cordis
source composite; no new Android device execution is claimed.

Headless Schedule dispatch passed the complete desktop platform suite (132 tests),
Agent desktop tests (18), dispatch desktop tests (2), both host builds, and both Android
instrumentation-source compilations. Actual private JAR tests execute due tasks without
rendering or UI registries, Markdown, localization, notifications or Goal services; they
verify current committed model settings, shared result publication, held runner cleanup,
stale callback rejection, failed replacement preservation and one restored runner.

The real Agent turn probe additionally exposed an invalid direct lookup of undeclared
optional Cordis services. Skills, Subagent and Overlay bindings now use independently
reactive private children, with identity-conditional withdrawal. Real provider turns probe
model allocation with and without these services and verify Skills recovery/withdrawal.

An additional actual-JAR test passed with active generation held in cancellation cleanup:
withdrawal waits for it, discards the unpublished result from durable history, and leaves
no floating result. Matching three real-APK lifecycle test sources compile. These runs
used the local Cordis source composite; APK source compilation is not new device execution.
Headless execution is complete for this stage; Schedule release aggregation remains pending.

Schedule release aggregation passed the complete desktop platform suite (135 tests),
the consolidated Schedule suite (12 tests), Conversation Execution tests (14), both host
builds and both Android instrumentation-source compilations. Production composition and
the trusted catalog now publish only `feature.schedule` for the three former releases;
the dispatch implementation, fallback resources and tests moved into `schedule`, removing
the `schedule-dispatch` Gradle/distribution module. The production subprocess loads 63
desktop-compatible archives; the catalog contains 70 logical packages, with 68 Android
ARM64-compatible and 67 other Android-compatible variants.

`ScheduleDispatchPrivateLifecycleTest` now also imports the actual complete aggregate,
checks private coordinator/completion implementation identity, executes without rendering,
withdraws tools and retained sessions, and restores a persisted pending task once after
re-enabling. Existing legacy dispatch entry tests remain explicit alternative compositions,
not production split releases. `PluginPackageIntegrationTest` exercises all three actual
old Schedule archives required by an external tool dependency and confirms that migration
retains the functional group while suppressing the new aggregate. Removing the dependency
allows cleanup and aggregate recovery. Disabled-choice/failed-commit coverage includes
Schedule aliases. The corresponding four APK lifecycle test sources compile.

These runs used the local Cordis source composite; APK compilation and archive packaging
are not new Android device execution evidence. Remaining scope is feature UI ownership,
namespaced settings persistence/commands and final cross-module verification.

Artifact presentation ownership and release aggregation passed the complete desktop
platform suite, Artifact module tests (5), the remaining default page tests (9), both host
builds and both Android instrumentation-source compilations. `artifact-tools` now owns
page, launcher, navigation, tool consumer and the `ArtifactFeaturePlugin` aggregate;
`ui-pages` no longer imports or mounts concrete Artifact implementations. Replaceable
Artifact storage and Web execution remain independent providers accessed through SDK
contracts. The trusted catalog has one `feature.artifacts` release in place of the three
former products, with 68 logical packages and 61 desktop-compatible archives.

`ArtifactLauncherTest` holds listing cancellation cleanup, verifies withdrawal joins it,
rejects retained list/launch actions and proves borrowed resources remain usable. The
private renderer receives a feature-owned action lifetime. Formal JAR/APK entry tests
load the full aggregate rather than individual presentation releases, preserving shared
SDK/private implementation identities and failed-replacement recovery. Actual old Artifact
archives are retained as a functional group when an external tool dependency survives;
removing the dependency permits cleanup and aggregate recovery. Disabled-choice and failed
migration commit coverage includes the three Artifact aliases.

An additional actual-JAR test then passed after withdrawing/recovering the default UI
service: Artifact tools remain registered without UI, and fresh presentation returns on
recovery. Complete feature withdrawal removes tool/page/navigation together while retaining
the generic settings page; re-enabling restores one tool registration. Its APK test source
compiled. Runs used the local Cordis source composite; this is not new Android device
execution evidence. Subagent and export presentation ownership, namespaced feature settings
and final cross-module verification remain pending.

API 48 Subagent presentation ownership passed SDK desktop tests (41), default UI
contract tests (5), Subagent tests (12), remaining page tests (9), the complete desktop
platform suite (137), both host builds and both Android instrumentation-source compilations.
Status cards, running-state selection and detail sheets moved from `ui-pages` into
`subagents/subagentui`, mounted as an optional child of `feature.subagents`. The page
prepares generic header/composer contributions and measures compact composer space;
it imports no Subagent status UI. Shared transcript invalidation may still observe
neutral message fields, rather than implementing feature presentation.

The default UI contract now carries `ConversationDecorationPosition` and optional borrowed
haze context. API versioning is 48; the existing shared default UI namespace already
exports the new contract, and no private renderer/coordinator namespace was exported.
Old external packages require recompilation. Static and packaged Subagent metadata include
its optional UI capability without a separate release.

`SubagentUiPrivateLifecycleTest` imports the actual aggregate JAR and checks private
presenter/shared anchor identity, newer completed updates superseding older running state,
retained presenter withdrawal, optional UI recovery while tools remain active, complete
feature withdrawal and fresh presentation recovery while chat remains registered. A
corresponding actual-APK test source compiled. A final actual-JAR run also renders the
status cards through the real theme/localization composition. Withdrawn presenter/render
callbacks remain quiet; Markdown details have a plain-text fallback. The final module
compile/test rerun passed after formatting cleanup.

These runs used the local Cordis source composite; APK source compilation and packaging
are not new device execution evidence. Export presentation ownership, namespaced settings
and final cross-module verification remain pending.

API 49 Generation/root separation passed SDK desktop tests (41), default UI tests (5),
application settings tests (4), the complete desktop platform suite (137), both host
builds and both Android instrumentation-source compilations. `NavigationPageRequest.chat`
is now optional. The default root no longer injects Generation; generation-dependent
application effects and chat preparation pause independently. The default layout keeps
navigation visible when the selected chat route lacks Generation, including compact screens.
The existing shared default UI namespace covers the changed contract; no private namespaces
were added to host exports. Current ABI documentation and packaged releases were updated.

`PrivateApplicationRenderingTest` imports actual private application/page JARs, withdraws
Generation, checks the root stays Active, renders both desktop and compact navigation,
opens the actual settings renderer and commits a changed configuration. After Generation
returns, the running UI resolves that durable value and restores chat; the settings session
survives. Its hidden test section observes the actual private page's prepared request rather
than replacing the page. A registry probe is installed before the composition commit so
it appears in the committed frame, rather than relying on a live lookup. The actual-APK
root test source also checks root identity/Active state during Generation withdrawal and
recovery. It was compiled, not executed on a device.

The final run is recorded in `build/plugin-remediation-root-generation-full.log` and used
the local Cordis source composite. The initially attempted ImageComposeScene main-owner
semantics check did not observe the settings Dialog; the final test observes the private
page preparation and committed configuration instead. Remaining root requirements include
History, Sessions and Localization; this stage does not claim complete root independence.
Namespaced settings, unified UI/command commits, export presentation ownership and final
cross-module/device verification remain pending.

API 50 History/Session root separation passed SDK desktop tests (41), default UI
contract tests (5), application settings tests (4), default page tests (9), the complete
desktop platform suite (137), both host builds and both Android instrumentation-source
compilations. Default application history/session projections are nullable; the default
root requires neither service, and the sidebar consumes prepared values instead of
injecting Sessions. Chat/effects are prepared only with their actual required interfaces.
Absent services do not publish substitute history or session providers.

Application composition identity now depends on its renderer and settings store rather
than borrowed history/artifact providers. Session lease disposal remains scoped to its
factory; settings drafts, pending commits and the open settings page survive unrelated
provider changes. Temporary route withdrawal does not overwrite the requested route ID;
the visible fallback route remains temporary and the requested route returns on recovery.

`PrivateApplicationRenderingTest` chooses chat explicitly, then independently withdraws
Generation, Sessions and History while actual private application/page JARs remain mounted.
Each case renders desktop/compact navigation, opens the actual settings page, commits a
changed configuration and verifies both the committed value and chat after recovery.
When History withdrawal changes the visible compact route to Artifacts, its sidebar action
still reaches settings. Codec private-loading tests retain suspended reader/writer checks,
revoked-handle rejection and restart/round-trip recovery, while requiring the unrelated
root to remain Active. Their corresponding real-APK sources were updated and compiled;
the default-APK root source checks renderer identity through all three withdrawals.

ABI 50 versions these shared default UI nullability changes within the already exported
namespace; current authority, both root READMEs, module documentation and generated
package fingerprints were aligned without exporting private implementations. The final
run is recorded in `build/plugin-remediation-root-sessions-full.log` and uses the local
Cordis source composite. This establishes desktop execution and Android compilation/
packaging evidence, not new device execution. Localization/root fallback, unified
namespaced settings mutations, export presentation ownership and final verification
remain required work.

API 51 feature-owned default UI texts and Localization/root separation passed SDK,
default UI, application, page, Localization, Web Search, Model Settings and Execution
Settings desktop suites, the complete desktop platform suite, both host builds and
both Android instrumentation-source compilations. `UiTextDictionary` and
`SettingsSection.texts` extend the existing shared default UI namespace; numbered
interpolation is neutral SDK code. Strict headless translation continues rejecting
unknown keys; rendering lookup returns null for missing keys and withdrawn catalogs.
No substitute headless Localization service is published.

Core page providers independently register English defaults compiled from private
`ui-pages/src/main/ui-texts` resources. Model, Search and Shell sections own their
corresponding defaults in their feature XML resources and register/release them with
the section. The common Gradle generator emits private bytecode without host resource
lookups. Duplicate default keys must have matching values; namespaces support arbitrary
external setting identities. Failed section registration releases newly registered
texts, stale cleanup leaves replacements intact and retained dictionaries expose revocation.

`PrivateApplicationRenderingTest` renders actual private JAR pages and Model/Search
forms without Localization, verifies fallback core labels, commits configuration and
recovers custom translations. It verifies private generated-text class identity,
extension-key fallback, immediate text withdrawal, and rejection of a retired root
catalog. Locale private-loading tests preserve revoked dictionary/command behavior and
headless withdrawal while checking that root/chat remain mounted through restart and
recovery. Corresponding actual-APK sources compile. These are desktop execution and
Android compilation/packaging results, not new Android device execution.

Execution Settings' late-UI fixture now awaits the complete expanding Fiber set, rather
than one pre-child snapshot; its Shell contribution and backend withdrawal checks pass.
The final run is recorded in `build/plugin-remediation-ui-texts-full.log`, using the local
Cordis source composite. Current ABI authority/documentation is 51, with no private
implementation namespaces added to host sharing. Unified namespaced settings storage/
commands, export presentation ownership and final completion verification remain pending.

### API 52 shared settings transactions

`KcodeSettings` publishes one transaction-capable store. Its operation owner covers
queued transactions, latest-state reads, validation callbacks and durable commits;
withdrawal cancels and joins that work. Commits are single-use, reject escaped references
and reject recursive writes. Raw persistence providers still implement load/save and
own their resource closure; the SDK coordinator does not close borrowed storage.

`SettingsCommandsPlugin` uses this transaction instead of a private command mutex.
Registered feature lifetimes remain nested around the durable commit. The default
application computes field differences from its draft, merges them into the transaction's
latest state and publishes the actual saved snapshot. Credential maps merge by complete
provider identity, including dotted IDs. Skipped intermediate UI writes retain their
independent field differences; failed saves do not publish execution configuration.

Regression coverage includes a real composed settings command interleaving with a UI
patch, stale UI drafts preserving command changes, independent credential preservation,
pending UI fields across skipped writes, transaction serialization, validation cancellation
and join, and escaped/reentrant commits. Native private-JAR/APK fixtures now distinguish
the shared coordinator from its private borrowed persistence implementation; durable
reopening and revoked references remain checked. The initial private-loader assertion
failure is retained in `build/plugin-remediation-settings-transactions-full.log`.

The terminal verification run in
`build/plugin-remediation-settings-transactions-verified.log` passed 208 tests:
SDK 45, default UI SDK 7, application 6, settings commands 12 and desktop platform 138.
Desktop host compilation, Android host assembly and both Android instrumentation source
targets passed using the local Cordis composite. `git diff --check` passed. Android
instrumentation was compiled, not newly executed on a device. Current ABI is 52; existing
settings and service SDK namespace exports cover the new contracts, and package ABI
descriptors were regenerated during the build.

UI differences still need feature-owned validation routing. Namespaced settings schemas,
legacy scalar migration and unknown-value preservation, export presentation ownership,
and the final whole-goal completion audit remain pending. This stage fixes shared storage
coordination and lost updates; it does not claim those remaining boundaries are complete.

### API 53 feature-owned export presentation

`ConversationExportFeaturePlugin` owns its private optional UI child as well as the
renderer, saver and exporter. The UI child requires only the typed UI registry and
export service, registers its private English dictionary and one presenter, and cancels
and joins its operations before withdrawing those registrations. It does not require
Localization. UI withdrawal leaves headless export services mounted and usable.

Conversation presenters prepare lists of generic placement projections once per page;
the new HeaderActions anchor supports feature-owned controls. Page context supplies
selection IDs, clear-selection and before-action callbacks. Export controls, Save/Share
menus, selection snapshots, asynchronous state and result notices are private to export.
Each page owns a graphics layer and joins its work before releasing it. The default root
and chat request no longer project an exporter, and chat contains no export action calls.
Compact New Chat renders independently of feature contributions. Generic message selection
uses a page-owned selection label rather than an export label.

`ExportUiPrivateLifecycleTest` loads actual private export/chat JARs, renders the real
compact chat, checks private generated-text identity, disables export and executes New
Chat, rejects retired presenters, and restores feature presentation. With the UI bridge
disabled it executes a real private exporter using an independent Compose render context,
then recovers UI without replacing that exporter. State tests cover immutable request/
selection capture, page withdrawal, feature cancellation/join and stale action rejection.
Existing composition and Localization tests now expect export's independent contribution;
their Android APK counterparts compile against the new presenter ABI.

The desktop test task now resolves its private search fixture classpath during execution,
avoiding premature dependency resolution that broke standalone test compilation. The
initial focused build demonstrated the configuration error; standalone compilation and
the complete suite pass after this correction.

The terminal run in `build/plugin-remediation-export-ui-verified.log` passed 250 tests:
SDK 45, default UI SDK 7, application 6, pages 8, Goal 16, Subagent 12, export 13,
Localization 4 and desktop platform 139. Desktop host compilation, Android host assembly
and both Android instrumentation source targets passed with the local Cordis composite.
`git diff --check` passed. This adds desktop execution and Android compilation/package
evidence, not new Android device execution. Current ABI is 53; existing default UI SDK
exports cover the changed constructors/list presenter contract, and private export UI
implementation namespaces remain unshared. Release metadata includes its UI contribution.

Namespaced settings schemas, feature-owned UI mutation validation, legacy persistence
migration with unknown-value preservation, and the final complete-goal audit remain pending.

### API 54 feature-owned command fields

`SettingsUpdate` now carries an immutable string mapping instead of enumerating model
and search fields. The SDK snapshots constructor inputs and returned mappings, rejects
malformed identities, preserves explicit empty values, and includes field identities
rather than credential values in diagnostics. Existing model and search wire names are
owned by their feature transforms, so current command clients keep their parameter names.
The Android receiver transports arbitrary string extras rather than maintaining a product
field allowlist. Unsupported or withdrawn fields still reject the complete command before
transforms or persistence; non-string Android extras fail transport parsing.

SDK tests cover external field identities, explicit clearing, defensive snapshots,
credential-free diagnostics and invalid identities. Registry tests register an arbitrary
external field, dispatch it without changing the command schema, and reject it after
withdrawal while retaining the saved value. This proves command extensibility; the fixture
uses an existing persisted field and does not establish arbitrary feature persistence.
Android instrumentation sources include the generic transport and non-string checks.

The terminal run in `build/plugin-remediation-generic-settings-commands-full.log` passed
212 tests: SDK 48, settings commands 13, model settings 4, Web Search 8 and desktop platform
139. Desktop host compilation, Android host assembly and both Android instrumentation
source targets passed using the local Cordis composite. The current model/search provider
sources also passed the follow-up run in
`build/plugin-remediation-generic-settings-providers-current.log`. Android instrumentation
was compiled, not newly executed on a device. Current ABI is 54; the constructor/accessor
change requires package recompilation, and existing settings SDK exports cover its types.

The command envelope and Android transport no longer impose feature field identities.
`StoredAppSettings`, repository defaults and scalar codecs still contain fixed feature
fields. Namespaced persistence, feature-owned schemas/defaults and UI validation, legacy
migration preserving unknown values, and the final whole-goal audit remain pending.

### API 55 namespaced persistence foundation

`StoredAppSettings.namespaces` carries opaque feature-owned JSON objects. New features
can persist documents without adding SDK fields or native scalar keys. Existing fixed
members remain as an explicit migration stage; their schemas/defaults still need to move
to their owning features. This does not complete the generic settings boundary.

Memory storage snapshots namespace maps and nested JSON objects/arrays on save and load.
MMKV serializes documents inside the existing committed snapshot and encrypted native
file. DataStore now publishes a complete snapshot in the same atomic edit as compatibility
scalar writes; reads prefer it and fall back to legacy scalars only when no snapshot
exists. The complete document passes through configured protect/reveal callbacks, so
private feature credentials need no field-name allowlist. The native desktop protection
policy remains application-data storage. Corrupt snapshots do not silently fall back.

SDK patch tests merge concurrent nested changes while retaining unrelated namespaces,
unknown fields, JSON nulls and dotted keys, and distinguish clearing from removal. Memory
tests reject nested caller-container aliasing. Scalar tests migrate legacy values, reopen
through a fresh codec, preserve opaque documents through unrelated writes and leave an
unknown native scalar intact. DataStore tests cover complete-document protection callbacks,
fresh store reads and retained namespace data while removing old credential maps. The
external command registration fixture now writes its own namespace and retains unknown
fields and disabled-feature documents after registration withdrawal.

`DesktopNativeStorageTest` saves an opaque feature document through an actual privately
loaded settings JAR, withdraws the provider, rejects the retired service, then remounts
and verifies the complete durable snapshot. Its Android APK counterpart includes the same
opaque JSON fixture and compiles; this stage adds no Android device execution.

The terminal run in `build/plugin-remediation-settings-namespaces-full.log` passed 218
tests: SDK 49, settings repository 11, application 6, settings commands 13 and desktop
platform 139. Desktop host compilation, Android host assembly and both Android
instrumentation source targets passed using the local Cordis composite. Current command
fixture verification is also recorded in
`build/plugin-remediation-settings-namespaces-command-current.log`. Current ABI is 55;
constructor/serializer changes require package recompilation. The JSON framework namespace
was already shared by `PluginHostApiPackages`; no private implementation export was added.

Built-in fixed-field removal, feature-owned schemas/defaults and UI validation, legacy
field migration with unknown-value preservation, and the final whole-goal audit remain
pending. Compatible snapshot transport and passing storage tests are not proof of those
remaining requirements.

### Feature-owned Web Search document migration

Web Search now owns its `feature.web-search` namespace schema: string `provider` and
an `apiKeys` object of string credentials. `SearchSettingsDocument` owns reads, defaults,
type validation, legacy interpretation and document writes. Search route defaults no
longer live in the storage provider. Command transforms and the optional form use the
same policy; the form trims only the selected credential rather than modifying unrelated
saved credentials.

The first configuration update imports legacy credentials into a complete feature
namespace without updating fixed legacy fields. Reads use legacy fields only when the
namespace is absent. An existing empty document uses feature defaults and never restores
legacy credentials; explicit empty strings remain empty. Unknown namespace fields,
unknown route credentials and unrelated feature documents survive updates. Invalid owned
types fail explicitly instead of falling back to old settings.

Policy tests cover migration/clearing, namespace precedence, unknown fields and disabled
feature data, invalid owned types and empty-document semantics. The actual bundled private
search JAR test saves a migrated document through the storage service, withdraws search,
rejects its old policy, re-enables it and verifies that a cleared credential remains empty
and unknown route credentials survive. Native storage/default assertions now check an
empty legacy search identity. The first full run caught the old Google-default assertion;
the corrected current fixture is verified in the subsequent run.

The terminal run in `build/plugin-remediation-search-settings-schema-verified.log` passed
229 tests: SDK 49, Web Search 11, settings repository 11, application 6, settings commands
13 and desktop platform 139. Desktop host compilation, Android host assembly and both
Android instrumentation source targets passed using the local Cordis composite.
`git diff --check` passed. Android tests were compiled, not newly run on a device.

This private implementation change retains ABI 55. Fixed SDK search members and native
compatibility codecs still remain for migration, alongside model/language/execution fields.
UI mutation validation through the durable shared transaction and the complete-goal audit
remain unfinished; feature-owned reads/writes are progress toward that boundary. Generic
patches also need coverage for concurrently creating the same previously absent namespace:
the current comparison emits an entire new object when the old key is absent. This is a
remaining merge boundary to fix before claiming complete unknown-value preservation.

### Concurrent creation of feature settings documents

The initial namespace merge boundary is corrected in `SettingsPatch`. Creating a missing
object first ensures that object exists, retaining any concurrently created object, then
applies differences to its individual fields recursively. Empty object creation remains
observable on an unchanged snapshot without deleting concurrent contents. Explicit JSON
nulls, array replacements and removals retain their prior semantics.

The reproduction in `build/plugin-remediation-new-namespace-patch-red.log` used a real
`ApplicationSettingsSession` draft created before a command committed the same feature
namespace; the original patch lost the command credential and unknown field. The fixed
session merges that draft into the latest transaction snapshot. SDK tests also cover
recursive object creation, independent credential entries, explicit empty strings, nulls,
arrays and concurrently populated empty objects. The focused run passed after the fix.

The terminal run in `build/plugin-remediation-new-namespace-patch-verified.log` passed
232 tests: SDK 51, application 7, settings repository 11, settings commands 13,
Web Search 11 and desktop platform 139. Desktop host compilation, Android host assembly
and both Android instrumentation source targets passed using the local Cordis composite.
`git diff --check` passed. This is desktop execution and Android compilation/package
verification, not new Android device execution.

This behavior change adds no public SDK contract or export and retains ABI 55. Remaining
schema migration and UI validation are not solved by a more precise generic patch.

The next model migration must consolidate the fixed-field reads in
`CatalogModelSettingsPolicy`, `ModelSettingsUpdate`, the model section description and
`ModelSettingsRenderer` into a feature-owned partial configuration codec. The renderer
must stop copying fixed credential maps before policy updates; it must preserve unknown
provider IDs/keys and fields in the document. Catalog-driven policy resolution should
continue supplying the prepared root projection, so the application root does not decode
model JSON. Generic storage must preserve legacy inputs until their owner migrates them.

### Feature-owned model settings document migration

`ModelSettingsValues` and `ModelSettingsDocument` are private to model settings and own
its `feature.model-settings` document. The policy, command transform, section description
and settings renderer now read the same partial configuration decoder. Namespace string,
credential-object and numeric temperature types are checked by the feature. Unknown fields
are retained when its known schema fields are encoded. Namespace defaults are feature-owned;
legacy repository defaults still remain until generic legacy provenance/migration replaces
the fixed SDK members.

Commands and policy updates write only the model namespace. When it is absent, the feature
imports fixed legacy values; once present, missing namespace fields use its own defaults,
without bringing back stale legacy credentials. Empty selected keys are removed by commands
without dropping credentials for other provider identities. Unknown or unloaded provider
IDs remain persisted, while policy resolution still returns no execution configuration
for an absent catalog entry. The form encodes its edited credential map through the feature
document rather than copying SDK model fields, and trims only the selected saved key.
The default root continues consuming the neutral resolved model projection and does not
decode model JSON.

Document tests cover empty-namespace precedence, strict owned field types, unknown fields,
unknown provider IDs, selected-key removal and retained disabled-feature documents. Updated
command tests inspect persisted namespace data instead of the untouched compatibility
members. Actual private model JAR tests encode and resolve the document, withdraw/restart
its provider, and resolve the same document through the recovered policy. The APK counterpart
has the same document assertions. The first complete run caught an obsolete assertion
that a save returned the original fixed-field snapshot; current fixtures expect the new
namespace without removing legacy inputs.

The terminal run in `build/plugin-remediation-model-settings-schema-verified.log` passed
239 tests: SDK 51, model settings 7, settings commands 13, application 7, Web Search 11,
settings repository 11 and desktop platform 139. Desktop host compilation, Android host
assembly and both Android instrumentation source targets passed using the local Cordis
composite. `git diff --check` passed. Android tests were compiled, not newly executed on
a device. No paid model/search request is established by these policy and package tests.

This private implementation migration retains ABI 55 and adds no shared implementation
namespace. Fixed members/codecs/defaults, language/execution configuration migration,
feature validation owned through durable UI commit, and the complete-goal audit remain
pending. This stage does not claim their completion.

### API 56 feature-owned language preference projection

`TranslationCatalog.languageSettings` optionally exposes the neutral
`LanguageSettingsPolicy` contract. Its default is null, so rendering-only catalogs do
not claim configuration support. The dictionary feature owns a private revocable policy
that parses/writes its `feature.localization` document (`language` string), validates
selected languages and chooses its own configured default for missing/unsupported values.
The generic root obtains the preferred language through that projection and falls back
to catalog metadata when it is absent; it no longer reads the persisted language scalar.
Its private text wrapper borrows the policy and revokes only its own projection on close.

The feature language form and description consume that same policy and the form is
visible only when the active catalog supports configuration. With no namespace the
feature reads the legacy scalar; updates write only the namespace. Existing empty
documents use the dictionary default, and unknown stored locale identities remain saved.
Unknown document fields and unrelated/disabled-feature data survive updates. Retained
policy references reject reads/updates after withdrawal, and the catalog then projects null.

Policy tests cover namespace precedence, legacy compatibility, configured defaults,
strict field types, choice validation, unknown data and stale references. Application
tests cover optional fallback and borrowing without closing the provider. Actual private
localization JAR tests verify private policy implementation identity, namespace updates,
withdrawal/stale calls and recovery after runtime restart. Their APK counterparts include
the same assertions and compile. Existing root withdrawal/recovery rendering tests also pass.

The terminal run in `build/plugin-remediation-language-policy-full.log` passed 247 tests:
SDK 51, Localization 7, application 8, model settings 7, Web Search 11, settings repository
11, settings commands 13 and desktop platform 139. Desktop host compilation, Android host
assembly and both Android instrumentation source targets passed with the local Cordis
composite. `git diff --check` passed. Android instrumentation was compiled, not newly run
on a device. Current ABI is 56; the new optional property/interface requires package
recompilation. Existing localization and settings SDK exports cover the new public types;
the private dictionary policy namespace is not exported.

Fixed compatibility members/codecs/defaults still remain. Execution/permission feature
schemas and root/host projections, feature validation through durable UI commit, legacy
migration preserving unknown values, and the whole-goal completion audit remain pending.
This stage establishes language ownership and root decoupling without claiming those
remaining requirements are complete.

### API 57 default Shell settings policy and root projection

The default execution-settings provider owns a private namespace policy for
`feature.execution-settings` (`mode` string). The optional neutral
`ShellModePolicy.settings`/`ShellModeSettingsPolicy` contracts expose resolution and
immutable updates; callback-only policies use null. `ApplicationViewServices` prepares
that optional capability. The root's Shell notification no longer decodes the fixed
scalar, and the execution reader, form and section description use the same feature-owned
configuration. The root still requires only settings infrastructure, not this feature.

Absent namespaces import the old scalar. Updates write only the feature document, retaining
legacy inputs, unknown fields and unrelated/disabled feature data. Empty namespaces use
App, unknown codes remain saved with App execution fallback, and malformed owned types
fail. Provider withdrawal removes its projection, rejects retained configuration callbacks
and cancels/joins asynchronous mode reads. Existing backend contribution tests still verify
one section while either system/Ubuntu backend remains and withdrawal of the last section.

The private Android Shell/policy fixture now checks configuration implementation identity,
saves a namespace-selected App mode and reads it through the execution service. Its new
assertions compile but were not newly run on a device. Common tests cover migration,
namespace precedence, unknown data, strict types, withdrawal and callback-only policy
capabilities. SDK/default UI contract changes require rebuilding for ABI 57; their existing
SDK namespaces cover the types, with no private implementation export.

The first complete run exposed a Localization lifecycle fixture that advanced virtual test
time while Cordis registrations used a real dispatcher. Its bounded registration wait now
runs on the real dispatcher with the same timeout, rather than racing virtual time against
provider startup. This fixes the test's synchronization and does not change localization
production behavior.

The terminal run in `build/plugin-remediation-shell-policy-verified.log` passed 259 tests:
SDK 51, default UI SDK 7, execution settings 5, application 8, Localization 7, model settings
7, Web Search 11, settings repository 11, settings commands 13 and desktop platform 139.
Desktop host compilation, Android host assembly and both Android instrumentation source
targets passed using the local Cordis composite. `git diff --check` passed. This is desktop
execution and Android compilation/package evidence, not new Android device execution.

Direct legacy mode decoding remains in `capability-providers/CapabilityProviders.kt` and
the `AndroidNativeSettingsShellPlugin`/`AndroidNativeSettingsUbuntuShellPlugin` alternatives
in `native-execution`. These paths must consume `KcodeShellMode`, with composition-owned
policy wiring for caller factories. The default packaged Android path already consumes
that SDK policy. Permission schema/projection and its chat controls, fixed compatibility
member/default cleanup, durable feature validation and final whole-goal audit remain pending.

### Unified Shell consumers and explicit policy composition

`SettingsShellProviderPlugin`, `SettingsUbuntuShellProviderPlugin` and the legacy native
Android settings entry classes now inject `KcodeShellMode` and delegate mode reads to its
policy. They no longer import a settings implementation or decode storage fields. Their
entry names and configuration shapes remain available; explicit compositions supply the
settings-backed or caller policy separately. Only the execution feature's compatibility
reader and repository migration codecs retain direct scalar decoding.

Desktop factory fixtures explicitly compose `SettingsShellModePlugin` as a test dependency.
Both system/Ubuntu paths observe namespace mode in preference to a conflicting legacy
scalar and retain settings replacement/cancellation coverage. A new test runs both adapters
with defaults disabled and no settings provider, replaces the caller policy, rejects retired
backends, withdraws/re-enables the policy and verifies consumer recovery and mount counts.
Android private factory fixture composition and injection now use the mode service, with
namespace selection assertions compiled against its private APK. Provider imports added to
platform builds are test-only; production module dependency edges are unchanged.

The terminal run in `build/plugin-remediation-shell-consumers-final.log` passed 260 tests:
SDK 51, default UI SDK 7, execution settings 5, application 8, Localization 7, model settings
7, Web Search 11, settings repository 11, settings commands 13 and desktop platform 140.
Desktop host compilation, Android host assembly and application/platform/native-execution
Android instrumentation source targets passed using the local Cordis composite.
`git diff --check` passed. Android instrumentation was compiled, not newly executed on a
device. The initial new fixture needed the profile's defaults flag and a JSON import; the
final source and complete run passed. Public ABI remains 57.

Permission schema/projection and feature-owned chat controls, generic legacy persistence
migration and removal of fixed SDK members/defaults, durable feature mutation validation,
and the final whole-goal completion audit remain pending. Subsequent work is grouped by
complete configuration/consumer/UI chains before broad regression, as requested.

### API 58 interaction configuration and feature-owned composer controls

The interaction feature now owns `feature.interaction-settings` (`mode`, string), strict
namespace decoding and the Ask default. Its private settings policy imports the old scalar
only if the namespace is absent, preserves unknown fields/unrelated namespaces and rejects
retained calls after withdrawal. `InteractionPolicy.settings` is an optional SDK capability;
callback-only policies expose no implicit settings control. The borrowed-approver composition
uses `SettingsApproverInteractionPlugin` from this feature instead of parsing storage in
`bundle-native`. The root prepares the optional policy only for committed host notifications.

The permission button and its six English defaults moved out of `ui-pages` into an optional
private child of the same interaction release. `ChatPageRequest` no longer has a concrete
permission mode, availability flag or callback. Generic `SettingsEditorProjection` and the
`ComposerActions` anchor provide drafts/submission/layout, while the feature owns decoding
and proposal creation. Removing default UI services suspends only the child; withdrawing the
feature removes its registration and dictionary and revokes retained configuration policy.
`ApplicationHostOptions.conversationSettingsControlsAvailable` enables generic conversation
configuration contributions. These SDK/default UI constructor changes require ABI 58 and
external-package rebuilds; no private implementation package is newly exported.

Three feature tests cover namespace precedence, strict types, unknown-data preservation,
legacy fallback and stale policies. Actual private desktop JAR assertions cover policy,
presenter and generated dictionary identity, namespace execution reads, control withdrawal,
UI-independent policy execution and recovery. The borrowed-approver path observes the same
namespace; Android private APK policy assertions cover namespace priority and stale policy
calls. The terminal `build/plugin-remediation-permission-batch-verified.log` run passed 263 tests:
SDK 51, default UI SDK 7, interaction settings 3, execution settings 5, application 8,
Localization 7, model settings 7, Web Search 11, settings repository 11, settings commands 13
and desktop platform 140. Desktop host compilation, Android host assembly and the
platform/application/native-execution Android instrumentation-source compilation passed.
Verification uses the local Cordis composite; no Android device instrumentation was newly
executed. The first attempt found missing required popup style arguments; the corrected
batch completed successfully. A Kotlin incremental-cache diagnostic fell back to full
compilation successfully. `git diff --check` passed.

The production plugin graph adds three composition/UI edges (115 plugin-to-plugin edges); module/release counts
are unchanged. Fixed compatibility DTO/default cleanup, generic legacy migration, durable
feature validation of UI mutations and the final whole-goal audit remain pending. Related
configuration, consumer, UI and assertions are changed as one batch before regression.

### API 59 opaque settings, historical migration and complete consumer cleanup

`StoredAppSettings` now contains only namespace objects and raw `legacyValues`. Model,
search, language, execution and permission owners interpret their old keys and defaults;
storage supplies none of these feature fields or choices. Missing historical fields stay
absent, explicit empty/zero/null values remain explicit, and unknown v1 root JSON remains
in the migration bag. Existing namespace data takes priority over historical values.

MMKV/DataStore load v2 first, migrate complete v1 snapshots next, then read historical
scalars through a bounded key-name map. Current saves publish one complete v2 snapshot;
there are no scalar fanout writes, credential key deletions, provider heuristics, or new
feature defaults in persistence. Old/native unknown keys and v1 snapshots remain untouched.
Corrupt committed records fail rather than falling back. DataStore protects every document
value; memory detaches legacy JSON and namespace containers. Legacy fixtures are explicit
test-only factories/getters, outside shared SDK exports and production compositions.

Headless schedule, native approval and Android notification consumers now borrow the
catalog configuration projection. The API helper delegates to the optional feature policy
or the rendering catalog's declared default; no consumer decodes a language persistence key.
A redundant settings-backed implementation was removed from callback-only `interaction`;
its native APK fixture uses the existing feature-owned borrowed-approver entry. Actual
private native approval fixtures declare English and Chinese dictionaries and verify that a
namespaced English preference overrides conflicting historical Chinese data.

The first broad compile exposed those remaining headless language readers; the full
consumer group was corrected together. The overlay module's Android instrumentation no
longer depends across source-set trees on `commonTest`: desktop/device presentation entries
compile only their shared scenario from a dedicated test-fixture folder with explicit
instrumentation dependencies. Production module/release counts and 115 plugin-to-plugin edges are
unchanged (17 additional edges target the independent UI library). SDK/default UI consumers require rebuilding for ABI 59, with no new private exports.

Verification passed through `allTests`, the complete desktop platform test suite,
`:apps:desktopApp:compileKotlin`, `:apps:androidApp:assembleDebug`, and Android
instrumentation-source compilation for platform-android, androidApp, native-execution,
settings-repository and conversation-overlay. Evidence:
`build/plugin-remediation-generic-storage-final-verified.log` (BUILD SUCCESSFUL).
This used JDK 21 and the local Cordis source override; instrumentation sources were
compiled, not run on a device. Durable feature-owned UI mutation validation and the final
requirement-by-requirement goal audit remain pending; no overall completion is claimed
by this schema migration.

Final factory review also found that Android excluded the interaction settings package
when a caller supplied a custom approver. The fallback then referenced a compile-only
feature class absent from the production host classpath. The factory now keeps the private
settings feature and registers the borrowed approver through neutral `KcodeToolApprovals`.
The native factory instrumentation fixture checks separate classloader identity, settings
replacement, approval replacement and rejection of stale approval callbacks. Its execution
still requires a device. The follow-up batch passed Android debug assembly and platform
instrumentation-source compilation; the complete desktop platform suite remained current
(`build/plugin-remediation-host-approval-batch.log`, BUILD SUCCESSFUL). The preceding broad
batch includes 268 passing tests across the ten affected desktop feature/infrastructure
suites and the desktop platform suite; this is a scoped count, not the total of `allTests`.

### API 60 feature-owned durable UI mutation validation

`KcodeSettings` publishes `mutations` for feature registration and `mutationStore` for
root proposals. The default application prepares this generic store without listing any
feature namespace or schema. It retains revisioned draft merging and the shared transaction
coordinator. Changed namespaces require their live registered owner; historical migration
values remain read-only. Unknown and disabled namespace data stays untouched when other
features change. Validation of multiple namespaces precedes one durable commit.

Model, search, language, execution and interaction settings own their validation rules.
Model/search/language register headless consumers independently of their UI and optional
command children. Execution/interaction policies register in their owning Fiber. Registered
owners cover validation and durable save; withdrawal cancels and joins admitted work.
Escaped/single-use commits and self-withdrawal are rejected before validation or registry
mutation. The command registry also preflights self-disposal before removing its entry.
Configured model temperature bounds now apply to command validation and normalize UI
proposals through the feature policy, matching execution resolution.

Verification covers generic transaction cancellation/cleanup, atomic multi-feature failure,
unknown namespace preservation, stale disposers, withdrawal/recovery and self-disposal.
The real desktop production-classpath subprocess checks invalid model/search/language/
permission proposals, a mixed valid/invalid mutation, and search disable/recovery with
unrelated permissions still editable. The common execution fixture checks the same
ownership path without UI or settings commands. Private rendering fixtures now propose
updates through feature policy instead of test-only historical setters. The Markdown
lifecycle fixture awaits actual Fiber settlement instead of advancing virtual time ahead
of framework work on real dispatchers.

The final source audit finds 50 plugin modules, 114 literal production plugin dependency
declarations, 17 UI library declarations, and no production dependency cycles. The older
115-reference count also included native-filesystem's extra private-JAR embedding reference;
it was not an additional dependency declaration. Direct cross-implementation imports among
ordinary consumers are limited to this feature's private capability adapter. Composition
and platform adapters remain the intentional provider selection/host boundary. Evidence:
`build/plugin-remediation-final-boundary-audit.log`.

The latest regression completed common/desktop tests, including 140 platform-desktop
tests, both host compilation targets, and five Android instrumentation compilation
targets. The scoped desktop suites contain 276 passing tests. Device verification found
that the custom Android approval input was configured as an override after its bundled
provider had been excluded. The factory now supplies that borrowed input as an explicit
feature mount. This final factory correction still requires compilation and device
reverification; device acceptance is not complete.


## Current limitations

- Jobs, independent subprocess/PTY services, full session logs, and filesystem observation/version guards have only [reserved contracts](harness-reserved-api.md). The default bundle does not supply placeholder implementations.
- UID-2000 drivers and independent Binder tests do not replace actual Shizuku authorization/connection or UID-0 environment verification.
- Tests without credentials do not establish paid model/search behavior; window tests do not establish global input injection.
- Specific test counts, installation results, and cold starts belong to individual verification records, not permanent product guarantees in this document.

See the [verification guide](verification.md) for execution commands and acceptance criteria.
