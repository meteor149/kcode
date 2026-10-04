# Feature ownership and verification entry points

This table describes source ownership for the current API 34. It does not repeat historical
test counts or migration conclusions. Neutral contracts live in [plugins/api](../plugins/api/README.md),
default UI protocols in [default-ui-api](../plugins/default-ui-api/README.md), and reusable
controls in [libraries/ui](../libraries/ui/README.md). See [bundle-native](../plugins/bundle-native/README.md)
for the default composition.

## Product features

Module names are relative to `plugins/`. Test names identify entry points; consult the
[verification guide](verification.md) for their actual scope and execution methods.

| Feature | Implementations and contributions | Main verification entry points |
| --- | --- | --- |
| Model adaptation, streaming chat, tool policy, and continuation | `llm`, `agent-loop`, `tools`, `system-prompt`, `continuations` | KoogClientLifecycleTest, OwnedAgentChatServiceTest, FormalModelAdapterPrivateLoadingTest |
| Model configuration resolution and connection policy | `model-settings`; `ui-settings` consumes the catalog | ModelSettingsPolicyTest, ModelSettingsPrivateLoadingTest |
| Language, dictionaries, and formatting | `localization`; feature plugins consume it explicitly | LocalizationPrivateLoadingTest, AndroidLocalizationPrivateLoadingTest |
| Session projections, send/stop/regenerate, and background generation | `session-history`, `conversation-execution`, `message-codec`; `native-notifications` provides Android foreground policy | GenerationCompositionTest, MessageCodecPrivateLoadingTest, ConversationOverlayLifecycleTest |
| History and settings storage | `history-repository`, `settings-repository` | HistoryResourceCompositionTest, SettingsResourceCompositionTest, AndroidSettingsStorageTest |
| ADB settings commands and UI settings commits | `settings-commands`, `application`; the host decodes Intents | SettingsCommandsCompositionTest, ApplicationSettingsSessionTest, AdbSettingsPluginTest |
| File IO, editing, and media reading | `native-filesystem` provider, `filesystem` consumer; `capability-providers` adapts contracts | NativeWorkspaceCompositionTest, AndroidNativeWorkspaceTest, CapabilityCompositionTest |
| System Shell, Ubuntu, and desktop execution | `native-execution` provider, `shell` consumer | NativeShellCompositionTest, AndroidSettingsShellTest, native-execution instrumentation |
| Permission modes and approvals | `interaction`, consumed by the agent loop | SettingsInteractionCompositionTest, NativeToolApprovalCompositionTest, AndroidNativeToolApprovalTest |
| Skill discovery, reading, and request instructions | `skills`, `skill-tools`, `system-prompt`, `agent-loop` | SkillRuntimeTest, SkillCatalogRendererTest |
| Subagent dispatch, messaging, waiting, and interruption | `subagent-provider`, `subagents` | OwnedSubagentFactoryTest, SubagentToolsTest, CapabilityCompositionTest |
| Goals, commands, and automatic continuation | `goal`; `goal-ui` provides presentation | GoalCommandHandlerTest, GoalSchedulePrivateLoadingTest |
| One-time/recurring scheduling and notifications | `schedule`, `schedule-dispatch`, `native-notifications` | NotificationCompositionTest, AndroidNativeNotificationsTest, GoalSchedulePrivateLoadingTest |
| Web search | `web-search-provider` provider, `web-search` consumer | WebSearchToolTest, FormalRuntimeEntryPrivateLoadingTest |
| Web Artifact repositories, saving, and opening | `artifact-repository`, `artifact-tools`; `web-container` runs artifacts | ArtifactProviderCompositionTest, AndroidArtifactProviderTest |
| Web containers, debugging, and native Web API bridges | `web-container`; the host declares platform manifest adapters | NativeWebContainerCompositionTest, AndroidNativeWebContainerTest, WebBackgroundContainerOverlayTest |
| Root UI, layout, sidebar, pages, messages, and navigation | `application`, `ui-pages`; protocols in `default-ui-api` | PrivateApplicationRenderingTest, FormalUiContributionPrivateLoadingTest, DefaultUiSlotsTest |
| Themes, settings sections, and Markdown | `ui-pages`, `ui-settings`, `markdown` | FormalUiContributionPrivateLoadingTest, MarkdownPrivateLoadingTest |
| Conversation image export and saving | `conversation-export` | ConversationExportLifecycleTest, ImageSavingCompositionTest, AndroidNativeImageSavingTest |
| System conversation overlays and standalone conversation presentation | `conversation-overlay`; `ui-pages` provides transcript/standalone slots | ConversationOverlayCompositionTest, AndroidConversationOverlayProviderTest |
| Plugin installation, enabling, replacement, and recovery | `inventory`, `installation-store`, `runtime`, platform Loaders | KcodePluginRuntimeTest, AndroidPluginCompositionTest, PluginCodeOriginTest |

## Host and composition boundaries

`apps` retains Activity/Window, process lifecycle, broadcast decoding, and generic platform
forwarding. SDK platform input, Binder/PFD, and native MMKV/SQLite leases preserve system
resources and cross-loader identity. These primitives do not own product dictionaries,
themes, pages, or storage policy. `test-support` is for tests only.

Default features run as built-in plugin compositions and can be overridden by external
JARs/APKs. Replaceability does not imply that the first application installation automatically
creates or installs every external package. Verify implementation identity, call revocation,
resource release, and state recovery separately.

## Current limitations

- Jobs, independent subprocess/PTY services, full session logs, and filesystem observation/version guards have only [reserved contracts](harness-reserved-api.md). The default bundle does not supply placeholder implementations.
- UID-2000 drivers and independent Binder tests do not replace actual Shizuku authorization/connection or UID-0 environment verification.
- Tests without credentials do not establish paid model/search behavior; window tests do not establish global input injection.
- Specific test counts, installation results, and cold starts belong to individual verification records, not permanent product guarantees in this document.

See the [verification guide](verification.md) for execution commands and acceptance criteria.
