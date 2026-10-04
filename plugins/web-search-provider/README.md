# HTTP search and search settings

`SearchSettingsProviderPlugin` publishes `KcodeSearchSettings` independently of HTTP transport.
Its private `HttpSearchSettingsPolicy` owns the built-in directory, default selection, API-key
requirements and compatibility with the historical Exa/Bright Data settings fields. The SDK
contains opaque route metadata and `SearchSettingsConfiguration`, with no provider enum or
implicit route selection. A replacement policy can publish different routes and interpret their
settings through the same contract.

`HttpWebSearchProviderPlugin` consumes settings and the current search settings policy. It owns
its private HTTP configuration, Ktor client/engine and cancellation lifecycle. A route outside
this implementation's supported transports fails explicitly, so replacing only the directory
cannot silently send a custom search request to Google. Transport and model-facing search tools
remain separate providers/consumers.

The settings UI renders the active directory, and settings commands delegate conversion to the
active policy. `StoredAppSettings.searchApiKeys` preserves credentials by arbitrary route ID.
The default policy reads historical credentials and synchronizes their fields when updating;
other route credentials survive a built-in selection change. Storage owns protection and atomic
publication. The stable scalar key prefix is `search_api_key.`; old snapshots without this map
remain readable.

Withdrawal removes dependent UI/command/HTTP contributions. A retiring UI can obtain a null
catalog; strict resolve/update calls reject a closed policy. External JAR/APK tests check actual
private class identity, catalog/configuration behavior, dependent Pending state and re-enable.
Unit/storage tests cover a custom directory, legacy keys, explicit clearing, unrelated key
retention, encrypted desktop persistence, failed writes and memory snapshot isolation.

Plugin API 27 requires older packages to be rebuilt. This module's tests do not establish the
completion of the broader shared/native resource audit.
