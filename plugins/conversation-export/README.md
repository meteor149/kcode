# Conversation export feature

`feature.conversation-export` / `ConversationExportFeaturePlugin` owns rendering, saving
and export orchestration and optional presentation in one install/enable boundary. Its children bind to the required
services independently. Missing Markdown suspends rendering and export; the saving resource
remains owned by the feature. Disabling the feature withdraws all three services, cancels
and joins owned calls, then releases saving resources.

The release takes `Unit`, selecting native saving from leased host inputs. A headless host
without native inputs preserves the explicit `Unsupported` saving result. In-process
composition may instead supply `ConversationImageSaverFactory`; allocation and closure
remain feature-owned. Other serialized configuration is rejected before allocation.

The internal `ConversationImageRenderingPlugin` publishes `conversationImageRendering`. The default
implementation owns the existing PNG layout and Markdown drawing. A replacement
receives a typed render request and caller-owned Compose render resources.

The internal `ConversationImageSavingProviderPlugin` publishes `conversationImageSaving`. Its factory
allocates a native saver per mount and releases it after joining cancelled calls.
Android MediaStore/FileProvider and desktop FileDialog/PNG implementations are
private to this module; hosts supply factories rather than UI business objects.

The internal `ConversationExportPlugin` consumes rendering and saving services and publishes
`conversationExport`. It selects messages in conversation order, redacts the active
secret and historical key patterns from the title/content, renders, and delegates
Save or Share to the injected `ConversationImageSaver` provider. It returns the bridge
result and truncation flag without localized UI messages.

The exporter owns each operation. Caller cancellation propagates; provider disposal
cancels and joins all operations before returning. A retained disposed exporter
rejects calls. Removing either rendering or saving provider makes the exporter Pending.
`ConversationExportUiPlugin` is a private reactive child requiring `uiSlots` and
`conversationExport`. It contributes header actions and result notices from one prepared
page state; chat supplies generic selection IDs and layout anchors. The feature owns its
menu, operation state, labels and English dictionary. Removing UI slots removes presentation
without removing headless export; feature withdrawal removes all contributions. New Chat
remains a generic chat action and does not depend on export.

UI operations belong to both the mounted presentation and their page lease. Withdrawal
cancels and joins operations before removing the contribution and its texts. Each page
allocates its own graphics layer and releases it after joining page work. Exporter and
text-measuring resources are borrowed; presentation never closes the exporter. Retained
actions reject calls after either owner closes, and cancelled exports publish no late notice.

Tests cover selection/redaction, custom renderers, share dispatch, cancellation,
stale handles, committed runtime rebinding, and a real Android APK renderer replacement.

API version 10 removes the caller-saver export argument, UI saver plumbing and
shared native classes. Native desktop PNG writes stage before replacing a selected
file; cancellation propagates and closes the EDT file picker. Android save rolls
back pending MediaStore entries before publication; successful URI handoff retains
share data. Tests verify real PNG pixels, pre-write cancellation, per-mount resource
release, pending/rebinding and isolated APK saving into the real MediaStore.


`AndroidNativeImageSavingPlugin` and `DesktopNativeImageSavingPlugin` remain legacy
native adapters. The aggregate acquires the same `PluginHostInputs` leases and factories
within its own lifecycle for both static and external deployments. Android's share launcher resolves the
current Activity through its lease and releases its callback on close. Desktop queries the
current parent Frame on EDT when creating its owned FileDialog. Explicit profile overrides
and headless factories remain supported.

Native distribution exports one dual-target package, `feature.conversation-export`.
The former `provider.export.image-rendering`, `provider.export.conversation`, and
`provider.export.image-saving` releases migrate together; required old external dependency
groups are retained, and disable choices and failed migration commits are preserved.
The entry class is in `ai.meteor.kcode.plugin.export`. Its implementations
are excluded from the host runtime classpath. Production-classpath tests render visible
Markdown pixels through the private JAR, and Android package tests write/read PNG pixels
through the private APK into MediaStore, cleaning up their own output. Renderer/saver
withdrawal suspends the exporter and rejects retained handles; recovery creates fresh services.

Android shares now use a unique subdirectory per handoff. Subsequent shares cannot remove
an earlier receiver's data; a failed/cancelled pre-handoff allocation removes only its own
file/directory, and successful handoff data survives provider removal. File names must be
nonblank basenames. The real APK test uses the actual entry, MediaStore pixels and FileProvider
URIs; it intercepts Activity handoff for deterministic verification and does not claim to
validate the foreground chooser UI. The actual desktop module JAR is restored and revoked
in integration tests; interactive FileDialog behavior still has a separate native UI gate.

The optional UI child contributes an Export row to the conversation More popup via
`MoreActions`. It opens save/share choices inside that popup; selection mode retains a
direct share control. The feature owns export state, text, dispatch and withdrawal guards.
