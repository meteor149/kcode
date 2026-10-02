# Application providers

This module supplies default providers for `settings`, `history`, `artifacts`,
`webContainers`, and `applicationUi`. Definitions live in `plugins:api`; platform
storage and container implementations remain in their platform source sets.

Each provider owns one Cordis service. Profiles can replace or disable it by its
stable inventory id, and consumers declare service dependencies rather than
importing these implementations. `ApplicationUiPlugin` accepts an
`ApplicationRenderer`; the default renderer preserves the existing `KcodeApp`
and design system. Platform lifecycle callbacks are supplied as
`ApplicationHostOptions`. The runtime publishes committed view services to the
Compose host, preserving UI state when unrelated tools change.

The model sees tools from the configured consumers. Read-only artifact providers
do not expose the save tool. Missing provider dependencies suspend consumers
until the provider returns. Replacing storage switches the interface used by
the UI; migrating existing stored data is the provider author's responsibility.

This is an application-level UI extension point. Individual pages, setting
fields, themes, and message renderers are not yet independent UI plugins.
Configuration is currently supplied by a Kotlin profile and is not persisted
automatically. See [the architecture guide](../../docs/plugin-architecture.md)
for lifecycle rules, tests, and the remaining integration work.
