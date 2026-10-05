# Settings command consumer

`consumer.settings.commands` consumes the current `settings` service and provides
`KcodeSettingsCommands`. Its operation owner covers the complete load/validate/save
command, serializes commands, joins cancellation cleanup, and revokes old handlers.
A committed result is returned only after save succeeds. Feature-owned contributions register supported fields and transforms through `KcodeSettingsCommands.updates`. Validation uses the
runtime's committed model catalog, including plugin-defined provider identifiers;
there is no static provider/model table fallback.

The stable request/result types live in shared settings contracts. The dispatcher is private to this module; model/search update policy belongs to the corresponding feature packages. Model/search command
validation tests migrated from the Android host; broadcast parsing, DUMP permission,
and result delivery remain native host bridges.

`ApplicationContent.updateSettings` resolves the current handler and committed
catalog, with backend work outside the runtime mutex. The Android receiver calls
this host entry, then emits SettingsChanged only on success. It never constructs an
MMKV store. Missing settings/command providers fail, and a replacement redirects
subsequent commands to its storage implementation. The Android application waits
at most five seconds for runtime publication during asynchronous startup; no active
runtime causes an explicit open-kcode error, never a storage fallback. Detaching an
old Activity cannot clear the newly published runtime.

Coverage includes validation, write failure, withdrawal during a suspended save,
private APK implementation with shared request/result types, and real shell-UID
broadcasts through the registered Android receiver. Compose UI validation and CLI
parsing are separate concerns. API 52 coordinates commands and UI differences through
the store published by `KcodeSettings`; transforms stay alive through its durable
commit. Direct writes to an independently retained raw backend do not participate.
UI patches still need the feature validation routing described in the remediation plan.

```sh
./gradlew :plugins:settings-commands:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidSettingsCommandsApkTest
./gradlew :apps:androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.AdbSettingsPluginTest
```

The last two filters target different test APKs and must run separately.

The dispatcher requires only `settings`, never `searchSettings` or model policy services.
Unsupported fields fail before any transform/save. A selected contribution owns the whole
transaction through durable save; withdrawing it cancels and joins in-flight operations.
Mixed requests validate against one registry snapshot, so missing features cannot cause
partial saves. Disabling search does not disable model updates, and vice versa.

API 54 accepts `SettingsUpdate(mapOf("plugin.example/field" to "value"))` without
changing the SDK request type or Android transport. Feature registrations own accepted
identities, including existing model/search wire names. Unsupported identities reject
the complete update before any transform/save. Values and field lists are request
snapshots; feature callbacks cannot mutate the caller's admitted envelope. This command
extensibility does not yet remove the legacy fixed storage fields or route UI differences
through feature validation; those migrations remain in the remediation plan.
