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

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.ui.layout
- provider.ui.sidebar
- provider.ui.settings

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
