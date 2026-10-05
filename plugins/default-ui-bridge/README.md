# Default UI registry and bridge

`UiSlotsServicePlugin` publishes `core.ui-slots` with Unit configuration. It requires
`KcodeUiContributions`, owns the private default UI registry, and registers its snapshot under
`DefaultUiSnapshotKey`. SDK modules contain shared identities and contracts only.

Each registration has a revocation token. Standard renderers, metadata and captured request
callbacks stop invoking withdrawn contributions. Page preparations use operation owners;
withdrawal cancels and joins them outside registry locks. Closing the registry rejects retained
calls, revokes all contributions and waits for cleanup, including concurrent close callers.
Withdrawing this bridge preserves unrelated neutral projections used by alternative roots.

Verify with `gradlew.bat :plugins:default-ui-bridge:desktopTest` and platform JAR/APK tests.
