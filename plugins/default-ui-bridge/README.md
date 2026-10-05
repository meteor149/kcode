# Default UI projection bridge

`core.ui-slots` / `UiSlotsServicePlugin` requires `KcodeUiContributions`, publishes
`KcodeUiSlots`, and registers `DefaultUiSnapshotKey`. It owns no concrete page/renderers.
Withdrawing this bridge retracts default UI contributions while leaving alternative root
projections registered in the neutral registry. Missing neutral infrastructure suspends the
bridge; restoration rebinds it. Concrete default pages are consumers in `ui-pages`.

Verify withdrawal isolation with `:plugins:default-ui-bridge:desktopTest` and real private
JAR/APK loading with the platform contribution tests.
