# Model settings policy

`provider.model-settings.catalog` supplies `KcodeModelSettings` through the shared
`ModelSettingsPolicy` contract. The implementation owns persisted model selection,
catalog membership and required connection-field checks, temperature normalization,
legacy DashScope region decoding, and configuration updates that retain other provider keys.

The default application declares this service as a dependency and receives the committed
policy through `ApplicationViewServices`. There is no shared implementation or implicit
fallback. Disable or uninstall suspends the default application; old policy references
reject both reads and updates. The pure synchronous methods allocate no jobs or resources.

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
