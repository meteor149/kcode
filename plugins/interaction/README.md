# Interaction policies

`provider.interaction.platform` publishes `KcodeInteraction`. The native default
`SettingsToolInteractionPlugin` in [interaction-settings](../interaction-settings/README.md) injects `KcodeSettings` and `KcodeToolApprovals`, reads
committed permission mode for each call and owns suspended operations. Either dependency
withdrawing makes the policy Pending and invalidates retained callbacks.

Native approvals are distributed separately by [native-tool-approvals](../native-tool-approvals/README.md).
This module consumes the SDK approval service without depending on its implementation.
`HostModeToolInteractionPlugin` permits an explicitly supplied mode callback while injecting
that service. `InteractionServicePlugin` remains an explicit callback entry. Settings-backed
approvers belong to `SettingsApproverInteractionPlugin` in the interaction-settings feature;
the duplicate settings implementation in this callback-only module has been removed. Native production hosts do not link
this implementation module. The composition layer borrows custom callbacks through
SDK-only `HostInteractionInputPlugin` for explicit policies, or the feature-owned
`SettingsApproverInteractionPlugin` for settings-backed custom approvers;
Android's `HostToolPermissionModeInputPlugin` borrows a mode reader and injects the approval
service. Each adapter owns admitted calls, joins them on withdrawal, and rejects stale
callbacks without closing borrowed inputs. Callback values are never package configuration.
The settings-driven Unit entry ships as an independent package and is excluded from
production host classpaths.

Lifecycle tests load the actual interaction-settings JAR/APK independently of the approval artifact,
then verify dependency withdrawal, cleanup waiting, restoration and replacement rollback.
