# Profile plugin management UI

This module provides a reusable, host-neutral Compose surface for managing plugin instances
through `ProfileManagementClient`. It has no dependency on default UI contracts, product
localization, or a running product root. `ui-profiles` contributes it as the plugin-only
settings page; the native host also renders it directly in plugin manager mode and during
Profile recovery. Its independent entry links to the complete Profile editor for selection,
creation, transfer, history and activation.

The standalone manager host prepares only native package metadata and Profile templates. It
does not create the selected product runtime on entry; activation is the first operation that
loads the selected plugin tree. On Android, when the main activity is already alive, its
host-owned client receives manager commands so activation follows the active runtime's
cancellation and publication lifecycle.

Edits publish revision-checked drafts. Package imports are verified Bundle imports that create
a draft Profile; users preview and explicitly activate it. Enable, disable, configure, add, and
remove operations use the same Profile compiler and activation transaction. Removing an
instance does not erase a package archive that may still be referenced by another Profile or
historical generation.

The UI and owned archive pickers target Android and desktop. Text defaults are sourced from
this module's XML dictionary so the independent host surface does not rely on product resources.
