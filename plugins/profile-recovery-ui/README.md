# Native Profile recovery surface

This host-linked module renders `ProfileHostContent` in the Android and desktop apps.
It is outside the replaceable Cordis product tree and does not depend on default UI contracts
or product localization. Ready hosts delegate directly to their own application renderer;
only recovery/transitions use the independent Material fallback through `KcodeTheme` and
the UI library's shared design defaults. Text defaults come from this module's XML dictionary.

Recovery reads saved definitions through the neutral `ProfileManagementClient` without
resolving the broken composition or querying its module catalogue. Users can select committed
intent, a draft or a historical generation. Historical intent is read-only and activates its
original frozen recipe directly; even save-and-activate does not write an implicit draft when
there are no edits. Copy a selection to a new draft to edit historical intent while retaining
its source recipe. Both the default settings page and recovery editor enforce this boundary.
Other selections support JSON editing, save, discard, saved activation and save-and-activate.
Invalid JSON/identity changes and stale revisions preserve the editor. Dirty edits prevent
selection/activation until explicitly saved/discarded. Refresh never updates a dirty editor's
write revision. Failed activation keeps the saved repair available for another attempt.
An unreadable selected definition cannot hide the remaining catalogue or template actions.

The native host supplies its distribution template independently of product services. Creating
a repair copy requires a new ID and uses create-only draft publication with separate settings,
history and workspace scopes. It retains the failed original and does not activate implicitly.
Template metadata failure is displayed without withdrawing other host management capabilities.
Templates are host-private metadata; the existing neutral client performs all writes/clones.

Accepted activation commands belong to the host. Losing a rendering observer does not cancel
them. Recovery does not repair corrupt repository authority or package staging failures that
occur before the native catalogue exists. Verified external Bundle import and credential-safe
portable export remain separate work; no default composition is silently imposed.

Validate with `:plugins:profile-recovery-ui:desktopTest`, both application build targets and
`AndroidProfileRecoveryUiTest` in the native Android instrumentation suite.
