# Model settings policy

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
[the plugin audit](../../docs/plugin-feature-audit.md). Current package ABI is 59; rebuild
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
