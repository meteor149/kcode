# Plugin SDK

This module defines the types that the native host and independently loaded plugins
share. It consolidates the former `shared` contracts with Cordis service keys,
extension events, host bridges, and the reserved Harness APIs.

- Agent, tool, workspace, shell, skill, model, and conversation contracts describe
  capabilities supplied by plugins.
- History, settings, artifacts, codecs, search settings, and image export contracts
  let providers and consumers exchange data without importing provider implementations.
- Localization contracts, text keys, and Compose adapters share translation identity;
  dictionaries and language selection policy belong to `plugins/localization`.
- Conversation state and application host options are interoperability contracts.
  Default layout, sidebar, pages, theme, and navigation protocols belong to the
  optional `plugins/default-ui-api`; reusable controls belong to `libraries/ui`.
- Native Binder protocols and leases share OS resources across plugin generations.
  Installed Android components belong to `plugins/platform-android`.

These contracts are loaded once by the host and exported through
`PluginHostApiPackages`. Copying them into each private JAR/APK would create distinct
class identities and break service lookup, casts, and cross-generation ownership.
Provider implementations remain independently loaded and replaceable.

The move retains package names and public signatures, including existing product
compatibility fields and localization keys. It does not turn those contracts into
implementation plugins or generalize all existing product schemas. Plugin API
version remains 34; no `:shared` Gradle dependency is required.

Run SDK tests with `./gradlew :plugins:api:allTests` (Windows: `gradlew.bat`, JDK 21).

UI contracts own their `UiSlotKey<T>` IDs and value types. `KcodeUiContributions.snapshot()`
prepares every registered projection; `snapshot(key)` prepares only one capability,
returns null when absent, and does not invoke unrelated providers. Both execute provider
code outside the registry mutex and retain the same cancellation/join semantics on
withdrawal. Only the owning UI interprets the returned value. Agent/tool event KDoc
records their dispatch modes and delegation semantics.
