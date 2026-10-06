# Native Profile recovery surface

This host-linked module renders `ProfileHostContent` in the Android and desktop apps.
It is outside the replaceable Cordis product tree and does not depend on default UI contracts
or product localization. Ready hosts delegate directly to their own application renderer;
only recovery/transitions use the independent Material fallback through `KcodeTheme` and
the UI library's shared design defaults. Text defaults come from this module's XML dictionary.

Recovery reads saved definitions through the neutral `ProfileManagementClient` without
resolving the broken composition or querying its module catalogue. Users can select committed
intent or a draft, edit JSON, save, discard, activate saved intent, or save and activate.
Invalid JSON/identity changes and stale revisions preserve the editor. Dirty edits prevent
selection/activation until explicitly saved/discarded. Refresh never updates a dirty editor's
write revision. Failed activation keeps the saved repair available for another attempt.

Accepted activation commands belong to the host. Losing a rendering observer does not cancel
them. Recovery does not repair corrupt repository authority or package staging failures that
occur before the native catalogue exists. Template restoration, historical selection and
package import remain separate work; no default composition is silently imposed.

Validate with `:plugins:profile-recovery-ui:desktopTest`, both application build targets and
`AndroidProfileRecoveryUiTest` in the native Android instrumentation suite.
