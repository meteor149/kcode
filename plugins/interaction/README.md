# Interaction and native approvals

`provider.interaction.platform` publishes `KcodeInteraction`. The native default
`SettingsToolInteractionPlugin` injects `KcodeSettings` and `KcodeToolApprovals`, reads
the current committed permission mode for each call, and owns its suspended operations.
Either dependency disappearing makes the policy pending and invalidates old callbacks.

`provider.tool-approvals.native` independently publishes `KcodeToolApprovals` through
`NativeToolApprovalPlugin`. Its portable JSON configuration contains `title`, `message`,
`allow`, and `deny` strings. The title formats one tool-name argument; the message formats
description (or tool name when blank) and input, bounded to 2,048 and 8,192 characters.
Templates use Java positional string formatting; malformed formats fail mounting.
`toolApprovalConfig` builds this configuration. Android localization resources and desktop
ResourceBundles belong to this module; external packages can supply their own scalar text.

The product entry acquires a per-mount SDK `PluginHostInputs` lease and uses the generic
`ConfirmationDialogHost`. Hosts provide window mechanics and lifecycle only. Withdrawal
cancels and joins pending dialogs before returning, and retired approvers reject new calls.
A host without dialog capabilities cannot mount this provider.

`HostModeToolInteractionPlugin` permits an explicitly supplied mode callback while still
injecting the approval service. The earlier `SettingsInteractionProviderPlugin` and
`InteractionServicePlugin` remain available for explicit custom approvers and headless
composition. Native Android deployment preserves existing custom-approver behavior.

Both native defaults and external APK/JAR packages use the same zero-argument product
entries. Lifecycle tests load the real module artifacts, including the settings policy,
then check dependency withdrawal, cleanup waiting, restoration and replacement rollback.
