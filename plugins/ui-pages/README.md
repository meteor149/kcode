# Conversation pages

This module owns `provider.ui.chat`, `provider.ui.conversation.standalone`, and
`provider.ui.navigation.chat`. `DefaultChatPagePresenter` resolves page capabilities during
frame preparation; the root supplies generic chrome, session and settings projections.
Missing page capabilities do not remove the settings or shell providers.
Chat navigation requires agent, history and model-policy services. Withdrawing the model
policy suspends chat rendering while the settings shell remains available for recovery.
Both compact and desktop headers expose a More popup. Feature plugins register rows through
`MoreActions` and use `ConversationPageContext.moreMenu` to open a page within that popup
or dismiss it. The page owns the anchor and popup chrome; export owns its row and choices.
Settings remain accessible through the sidebar.

Message/transcript rendering is reused privately from `ui-messages`. Layout/sidebar/settings
implementations live in `ui-shell`; the product theme lives in `ui-theme`. Existing release IDs,
entry point classes and independent enable states are preserved. Archives include only their
module's private dependency closure, rather than twelve copies of the entire default UI.

Subagent and other feature presentations remain feature-owned conversation contributions.
Pages consume prepared default UI slots and generic conversation anchors, without depending
on their concrete providers. UI defaults are compiled from `src/main/ui-texts`.

Verify with `gradlew.bat :plugins:ui-pages:desktopTest` and real JAR/APK rendering tests in the
platform modules.
