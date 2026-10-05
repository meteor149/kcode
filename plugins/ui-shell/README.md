# Default application shell

This module owns `provider.ui.layout`, `provider.ui.sidebar`, and `provider.ui.settings` through
`DefaultLayoutUiPlugin`, `DefaultSidebarUiPlugin`, and `DefaultSettingsUiPlugin` respectively.
They accept Unit configuration and publish independently reversible default UI contributions.
Settings forms and validation belong to feature packages; this module only orchestrates their
navigation and shared settings sheet from `libraries:ui`.

The sidebar consumes prepared projections. Missing sessions disable conversation actions while
preserving settings/navigation. Each provider registers its compiled English defaults independently.
The shell archive contains no conversation renderer or product theme implementation.

Verify with `gradlew.bat :plugins:ui-shell:desktopTest` and platform private rendering tests.
