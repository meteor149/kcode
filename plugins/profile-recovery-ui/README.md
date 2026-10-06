# Native Profile recovery surface

This host-linked module renders `ProfileHostContent` in the Android and desktop apps.
It is outside the replaceable Cordis product tree and does not depend on default UI contracts
or product localization. Ready hosts normally delegate to their own application renderer;
the separate Plugin Manager entry always uses an independent Material screen from
`profile-management-ui`, including while the product is healthy. Recovery and transitions
also use the host-linked fallback through `KcodeTheme` and the UI library's shared design
defaults. Text defaults come from private XML dictionaries.

Android registers `PluginManagerActivity` as a second launcher entry. Desktop accepts
`--plugin-manager` to open the same manager window without rendering the product root. The
manager links plugin operations to the full Profile editor; both use the host-owned
`ProfileManagementClient`. The standalone host prepares package metadata only and waits until
explicit activation to create a product runtime. If the Android main activity is already alive,
the manager delegates commands to its host-owned client so active work and runtime publication
remain coordinated. Invalid plugin configuration or failed product startup cannot remove access
to management and repair tools.

Recovery reads saved definitions through the neutral `ProfileManagementClient` without
resolving the broken composition or querying its module catalogue. Users can select committed
intent, a draft or a historical generation. Historical intent is read-only and activates its
original frozen recipe directly; even save-and-activate does not write an implicit draft when
there are no edits. Copy a selection to a new draft to edit historical intent while retaining
its source recipe. The standalone Profile editor and recovery editor enforce this boundary.
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
