# Artifact repository provider

The module owns manifest decoding and validation, web artifact resource staging,
manifest publication, rollback, and revocable repository facades. Shared code only
exports artifact models, repository/file-store contracts, and stable path constants.
Private Android/Desktop file stores belong to this module. Native entries create
the factory; each mount allocates its store and repository, and withdrawal waits for
owned calls before releasing the store. Native bundle id `provider.artifacts.platform` stays unchanged.

`FactoryFileArtifactsProviderPlugin` accepts `ArtifactFileStoreFactory` and owns
its returned resource. `FileArtifactsProviderPlugin` accepts a borrowed
`ArtifactFileStore` and does not close it; writable stores expose
`MutableArtifactRepository`, while read-only stores expose only `ArtifactRepository`.
`ArtifactsProviderPlugin` accepts an explicitly supplied repository for custom
compositions. The runtime's legacy host projection resolves the active provider
on each call and fails when absent, without retaining the original native instance.

Withdrawal cancels and waits for calls. Failed pre-commit saves roll back staging
and target resources under NonCancellable; cleanup failures are reported. Manifest
publication is the commit edge and cannot be interrupted into a dangling entry.
Once committed, later cancellation retains the artifact and its resources.

Common tests cover manifest compatibility, validation, copying, cancellation,
publication failure, and commit races. Desktop composition tests cover revocation
and rebinding. Real Android tests load this provider and its private manifest codec
from an isolated APK, and verify actual native files survive provider recreation.

API version 9 removes the former shared native stores and empty repository singleton.
Native path validation and atomic file operations are private plugin implementation.
Tree cleanup unlinks symlinks and Windows directory junctions without deleting
their targets. Tests cover actual Windows junctions, Android symlinks, private APK
factory loading, allocation cancellation, failed replacement, read-only capability,
and waiting for call cleanup before resource release.

Native default compositions use `AndroidNativeArtifactsPlugin` (`Unit`, leased SDK
host inputs) and `DesktopNativeArtifactsPlugin` (absolute deployment path string).
The entry owns factory creation and delegates allocation, revocation and release
to the owned provider. Default hosts no longer supply a business factory closure.
Explicit caller-owned stores/repositories and custom profile overrides remain supported.
Real JAR/APK formal-entry tests cover private implementation identity, stale-call
rejection, durable data after remount, and uninstall. Desktop storage packages
include the private Room runtime/common and collection dependencies; DataStore
and its Okio types preserve the host SDK identity.
