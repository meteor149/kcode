# Neutral UI contributions

`UiContributionsServicePlugin` publishes `core.ui-contributions` with Unit configuration.
Its private `OwnedUiContributions` implements the shared abstract `KcodeUiContributions`
service. Plugin-defined keys and projection values remain opaque to the kernel.

Projection callbacks execute outside registry locks, belong to per-registration operation
owners, and are cancelled and joined on withdrawal. Old cleanup preserves replacements.
The owning Fiber closes the registry; retained service references reject further calls.
Alternative roots can use this registry without loading any default UI vocabulary.

Verify with `gradlew.bat :plugins:ui-contributions:desktopTest`.
