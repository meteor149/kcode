# Conversation export providers

`provider.export.image-rendering` publishes `conversationImageRendering`. The default
implementation owns the existing PNG layout and Markdown drawing. A replacement
receives a typed render request and caller-owned Compose render resources.

`provider.export.image-saving` publishes `conversationImageSaving`. Its factory
allocates a native saver per mount and releases it after joining cancelled calls.
Android MediaStore/FileProvider and desktop FileDialog/PNG implementations are
private to this module; hosts supply factories rather than UI business objects.

`provider.export.conversation` consumes rendering and saving services and publishes
`conversationExport`. It selects messages in conversation order, redacts the active
secret and historical key patterns from the title/content, renders, and delegates
Save or Share to the injected `ConversationImageSaver` provider. It returns the bridge
result and truncation flag without localized UI messages.

The exporter owns each operation. Caller cancellation propagates; provider disposal
cancels and joins all operations before returning. A retained disposed exporter
rejects calls. Removing either rendering or saving provider makes the exporter Pending. UI uses
the committed nullable service and hides export controls when unavailable; chat
remains available. There is no direct UI call to the default renderer or save/share.

Tests cover selection/redaction, custom renderers, share dispatch, cancellation,
stale handles, committed runtime rebinding, and a real Android APK renderer replacement.

API version 10 removes the caller-saver export argument, UI saver plumbing and
shared native classes. Native desktop PNG writes stage before replacing a selected
file; cancellation propagates and closes the EDT file picker. Android save rolls
back pending MediaStore entries before publication; successful URI handoff retains
share data. Tests verify real PNG pixels, pre-write cancellation, per-mount resource
release, pending/rebinding and isolated APK saving into the real MediaStore.


`AndroidNativeImageSavingPlugin` and `DesktopNativeImageSavingPlugin` are Unit-configured
native entries. Both acquire `PluginHostInputs` leases; default platform composition uses
the same entries as external APK/JAR deployments. Android's share launcher resolves the
current Activity through its lease and releases its callback on close. Desktop queries the
current parent Frame on EDT when creating its owned FileDialog. Explicit profile overrides
and headless factories remain supported.

Android shares now use a unique subdirectory per handoff. Subsequent shares cannot remove
an earlier receiver's data; a failed/cancelled pre-handoff allocation removes only its own
file/directory, and successful handoff data survives provider removal. File names must be
nonblank basenames. The real APK test uses the actual entry, MediaStore pixels and FileProvider
URIs; it intercepts Activity handoff for deterministic verification and does not claim to
validate the foreground chooser UI. The actual desktop module JAR is restored and revoked
in integration tests; interactive FileDialog behavior still has a separate native UI gate.
