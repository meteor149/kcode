# Profile management UI

`DefaultProfileUiPlugin` is the optional `provider.ui.settings.profiles` package entry.
Its child contributes a `profiles` settings section when both `KcodeProfiles` and
`KcodeUiSlots` are available. Missing services suspend that child without imposing
management or default layouts on independent roots. Provider withdrawal removes the
contribution and cancels/joins its queries and command observers.

The screen supports catalogue selection, creation, cloning, JSON definition editing,
revision-checked draft saving, explicit discard, verified preview, effective tree and
diagnostics, module catalogue, history selection, activation and confirmed deletion.
Deletion confirmation captures the target and authority revision. Unsaved edits retain
their original revision across refreshes; stale publication cannot overwrite another writer.
Names, Bundle order, operations and scopes can be edited in the definition document.
Creation and cloning use isolated business-data scopes and do not copy business data.

Accepted activation belongs to the native host. Removing this UI cancels its observer,
not the accepted command. Explicit cancellation uses the retained command handle. Recent
command status comes from the host stream, so a reconstructed screen can see the result.
The package owns English/Chinese resource defaults, follows the app language, and permits
custom translation catalogue overrides. Spacing and colors use shared design tokens.

New native templates select `kcode.default-ui` version 2, which includes this module.
Existing committed generations retain their frozen version 1 Bundle definitions; they
do not silently acquire new UI. Explicit Profile editing can insert
`provider.ui.settings.profiles` when that module is available.

This is the first management surface. Visual tree-operation forms, unsaved-edit confirmation
on navigation away, import/export, independent recovery UI, and rendered desktop/device
acceptance remain outstanding. Unit tests establish session behavior; native tests establish
actual private JAR contribution registration, withdrawal and recovery, not visual acceptance.
