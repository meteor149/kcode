# Settings-driven interaction policy

`SettingsToolInteractionPlugin` is the Unit-configured entry for
`provider.interaction.platform`. It injects the neutral SDK `KcodeSettings` and
`KcodeToolApprovals` services and publishes `KcodeInteraction`. Each permission-mode call
reads committed settings, with unknown values falling back to `Ask`; approval calls use
the separately mounted approval provider. The plugin owns and cancels admitted operations,
and retained callbacks reject work after withdrawal.

The native desktop default and settings-driven Android default load this entry from an
independent JAR/APK package. Production hosts do not contain its implementation class.
The callback-configured alternatives remain in [interaction](../interaction/README.md),
and native Android composition retains those alternatives when custom mode or approval
callbacks are supplied. Headless profiles do not acquire this default package implicitly.

Bundled distribution fixtures verify private implementation identity, current settings
reads, withdrawal/recovery, stale callbacks and agent-loop suspension on both platforms.
`NativeToolApprovalCompositionTest` and `AndroidNativeToolApprovalTest` additionally verify
independently loaded policy and approval artifacts, dependency withdrawal and cleanup.

API 58 exposes optional `InteractionPolicy.settings` through the neutral SDK. This package
owns `feature.interaction-settings` (`mode`, a string), strict decoding, the Ask default,
and import of the old scalar only when no namespace exists. Empty/unknown modes do not
resurrect a legacy Bypass value. Updates preserve unknown fields and other namespaces.
The default UI child owns the composer permission button and English fallback dictionary;
it contributes through `ComposerActions` and a generic `SettingsEditorProjection`, using
the application's draft/transaction submission boundary. Missing default UI services
suspend only this child. Withdrawal removes its controls and revokes retained settings
policies and callbacks while preserving persisted documents.

`SettingsApproverInteractionPlugin` is the composition entry for an explicitly borrowed
custom approver. It uses the same feature-owned settings policy; the native bundle no
longer decodes permission settings. Callback-only interaction policies expose no settings
capability and therefore acquire no implicit permission control.

API 59 reads the historical permission key from opaque `legacyValues` only when its namespace
is absent. The repository supplies no permission field/default; absent values select this
feature's Ask fallback. Storage migration does not validate or choose permission semantics.

API 60 registers feature-owned namespace validation with `KcodeSettings.mutations`.
The default root uses `KcodeSettings.mutationStore`: proposals merge into the latest
transaction state before validation, and the registration lifetime covers durable save.
Withdrawal rejects subsequent edits of this namespace while preserving its saved data;
other active settings owners remain usable. Validation is independent of settings-command
and default UI availability. Feature rules validate types and newly selected values while
retaining unchanged future identities and unknown document fields.
