# Settings command consumer

`consumer.settings.commands` consumes the current `settings` service and provides
`KcodeSettingsCommands`. Its operation owner covers the complete load/validate/save
command, serializes commands, joins cancellation cleanup, and revokes old handlers.
A committed result is returned only after save succeeds. Validation uses the
runtime's committed model catalog, including plugin-defined provider identifiers;
there is no static provider/model table fallback.

The stable request/result types live in shared settings contracts. Update policy
and the Consumer implementation are private to this module. Model/search command
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
parsing are separate concerns; command serialization does not claim a global
transaction across every independent settings client.

```sh
./gradlew :plugins:settings-commands:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidSettingsCommandsApkTest
./gradlew :apps:androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.AdbSettingsPluginTest
```

The last two filters target different test APKs and must run separately.
