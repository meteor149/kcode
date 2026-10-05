# Default message presentation

This module owns `provider.ui.conversation.transcript`, `provider.ui.message.user`,
`provider.ui.message.assistant`, `provider.ui.message.error`, and `provider.ui.tool.default`.
Their corresponding Default* plugins accept Unit configuration and register independent typed
renderers through `KcodeUiSlots`. Package IDs and entry point classes are unchanged.

Conversation pages reuse the message/selection/scroll implementation as a private dependency.
The SDK and component library retain shared identity across independent JAR/APK class loaders.
Transcript and message archives contain no shell, settings page or theme implementation.

Verify with `gradlew.bat :plugins:ui-messages:desktopTest` and platform private loading tests.
