# Common model management

`LlmServicePlugin` is the Unit-configured entry point for `core.llm`. It creates its private
`OwnedLlmRegistry` implementation of the SDK `KcodeLlm` contract during Cordis apply; model adapters inject this service and own their
registrations. The SDK owns only the abstract registry contract; registry state and adapter/client lifetime wrappers belong to this module.

Native distributions ship this provider as an independent `.kplugin` with desktop JAR and
Android APK variants. Host production classpaths exclude this module. Disabling `core.llm`
withdraws adapter registrations, suspends dependent consumers, and revokes retained adapter
handles. Re-enabling creates a new registry and rebinds providers without changing settings
or saved credentials. Production package fixtures cover both platforms and this lifecycle.

The provider-specific clients, catalogs and generated model-label dictionaries remain in
`plugins/llm`; they ship as independently loaded model-adapter packages. This module introduces
no vendor-client dependencies. In-process compositions that mount
`LlmServicePlugin` directly must include this module on their runtime classpath.

## Model settings policy

`provider.model-settings.catalog` supplies `KcodeModelSettings` through the shared
`ModelSettingsPolicy` contract. The implementation owns persisted model selection,
catalog membership and required connection-field checks, temperature normalization,
legacy DashScope region decoding, and configuration updates that retain other provider keys.

The default application receives the optional committed policy through
`ApplicationViewServices`. There is no shared implementation or implicit fallback.
Disable or uninstall removes the feature's model settings form while the settings root
remains usable; old policy references reject both reads and updates. The pure synchronous methods allocate no jobs or resources.

The provider accepts `Unit` for its original temperature range of 0–1, or a JSON object:

```json
{"minimumTemperature": 0.0, "maximumTemperature": 2.0}
```

Bounds must be numeric, finite, between 0 and 2, and ordered. Unknown fields are rejected
before replacing the active mount. Non-finite persisted temperatures do not produce a
usable execution configuration. Missing catalog entries never choose another model.

Common tests cover required fields, configuration validation, absent models, key retention,
and disposed references. Desktop JAR and Android APK tests cover private implementation
identity, changed policy behavior, invalid replacement, persisted disable across restart,
explicit re-enable, and uninstall without reviving the built-in provider. Actual application
rendering tests observe the configured temperature through a UI effect across replacement
and withdrawal.

The same feature JAR/APK contains its default settings form. The feature entry mounts an
optional settings contribution as a child Fiber; missing `uiSlots` suspends only this
child. Disabling/replacing the feature withdraws its setting item, without withdrawing
the settings page. Restoring the feature registers a fresh contribution. There is no
separate UI-only settings package.
Its command contribution similarly depends on `settingsCommands` and registers only
its own fields. Missing settings commands do not suspend the feature policy or UI.

Settings English defaults live in `src/main/ui-texts/strings_en.xml` and compile
into this feature's private bytecode. They register with the section and disappear
with it; the page does not supply this feature's field labels or read host resources.

Model configuration now uses the feature-owned `feature.model-settings` document.
`ModelSettingsValues` and `ModelSettingsDocument` own partial configuration decoding,
namespace defaults, field type checks and updates. Policy resolution, command parsing,
settings descriptions and forms use that decoder. String fields retain their legacy
names; `modelApiKeys` is an object of provider IDs to strings and `temperature` is numeric.
Unknown fields, unloaded provider identities/credentials and other feature namespaces
survive updates. A missing catalog entry still produces no execution configuration.

When the namespace is absent, its owner imports its keys from opaque `legacyValues`.
Missing fields use this feature's defaults; explicit empty strings and zero remain explicit. Updates write
only the namespace and leave legacy values intact as migration inputs. Once present,
namespace values take precedence, including explicit empty credential strings/maps.
An empty document uses feature defaults without recovering stale legacy secrets.
The form retains credentials for other routes without trimming them. Clearing a selected
credential through commands removes only that route from the feature document.

API 59 removes fixed SDK members and repository product defaults. Historical interpretation
and current schema validation remain in this feature; storage preserves raw inputs and
unknown data. Complete feature validation through durable UI commit remains tracked in
[the plugin audit](../../docs/plugin-feature-audit.md). Current package ABI is 63; rebuild
external packages against the current shared SDK.

API 60 registers feature-owned namespace validation with `KcodeSettings.mutations`.
The default root uses `KcodeSettings.mutationStore`: proposals merge into the latest
transaction state before validation, and the registration lifetime covers durable save.
Withdrawal rejects subsequent edits of this namespace while preserving its saved data;
other active settings owners remain usable. Validation is independent of settings-command
and default UI availability. Feature rules validate types and newly selected values while
retaining unchanged future identities and unknown document fields.

Configured temperature bounds are shared by durable UI validation, explicit command
validation and execution resolution. UI proposals normalize a model's default temperature
through the feature policy, while explicit out-of-range command values are rejected.

This module also owns model configuration, namespace validation, commands and optional
settings UI. `provider.model-settings.catalog` retains its independent plugin ID and lifecycle.


Provider implementations are separate modules below `plugins/llm`. This module owns
registry and configuration policy, command validation and optional standard UI. Providers
inject the SDK registry and register one catalog/client-factory lifetime. Both settings
and conversation selectors read the committed catalog; providers need no default UI.

API 63 carries provider-declared `iconId`, `regionChoices` and `regionMigrationKey`.
The form renders generic metadata without a vendor switch. The selected provider owns
choice labels, defaults and historical migration-key declarations. Generic region values
are validated on command updates, durable mutations and execution resolution. Historical
fields remain preserved, and disabling a provider retains its credentials and saved IDs.

Registry withdrawal closes and joins every retained adapter/client, rejects stale registry calls,
and awaits concurrent closes. Provider support callbacks execute outside registry locks.
