# Publishing Profile Bundles

`ProfileBundleArchiveWriter` builds the [Bundle archive format](profile-bundle-archives.md)
from a portable `ProfileBundle`, native code archives with SHA-256 identities, declared
`PackageTarget` values and an output file. The JVM implementation is shared by Desktop
and Android. It is publisher tooling, not a Profile activation or committed-state export.
SDK API remains 73.

## Command-line build

Create a Bundle definition using the shared SDK operation format:

```json
{
  "id": "example.dictionary.bundle",
  "version": "1.0.0",
  "patches": [
    {
      "type": "insert",
      "entries": [
        {
          "id": "dictionary",
          "packageId": "feature.localization",
          "config": { "defaultLanguage": "en" }
        }
      ]
    }
  ]
}
```

The code package must provide `feature.localization`. Build or obtain its native `.kplugin`
archive and calculate its lowercase SHA-256. The Bundle ID/version become the Cordis
container identity and must satisfy its package ID and semantic version rules.

Create a pack request next to `bundle.json`:

```json
{
  "bundle": "bundle.json",
  "code": [
    { "archive": "release.kplugin", "sha256": "<lowercase-sha256>" }
  ],
  "targets": [
    { "system": "windows", "arch": ["x86"], "bits": [64] },
    { "system": "linux", "arch": ["x86"], "bits": [64] }
  ],
  "output": "dist/dictionary.kbundle"
}
```

On Windows with JDK 21:

```powershell
.\gradlew.bat :plugins:profiles:packProfileBundle -PprofileBundleRequest=C:/path/to/request.json
```

When building against a local Cordis checkout, also pass `-PcordisSource=C:/path/to/cordis-kotlin`.
All relative paths resolve against the request file's directory, regardless of the launch
directory. Absolute paths are also supported. Successful execution prints the output path
and archive SHA-256. Bundle and request JSON reads use strict UTF-8 and a 2 MiB bound.
Request schemas reject unknown fields. A data-only Bundle can omit `code` or use an empty list.

## Validation and ownership

The writer snapshots Bundle/target data before suspending, streams code into its own temporary
payload, bounds per-code size and total expanded size using Cordis limits, and validates each
copied archive against the supplied digest. It infers code IDs/versions from those verified
containers, rejects duplicate code addresses/IDs and sorts code declarations by ID. Reordering
the code input list therefore does not change output bytes for equivalent inputs. Bundle patch
order remains significant. Manifest file metadata, root dependencies and digest-addressed
payload paths are generated rather than supplied by the publisher.

Cordis verifies the generated manifest and all payload records, inspects the completed archive,
then atomically replaces the output. Invalid inputs do not replace an existing output. Normalized
output paths may not equal a code input; the CLI also rejects its request/definition paths.
Owned temporary payloads are removed on success, failure and cancellation, attempting all
releases if one deletion fails. Cancellation checks run while streaming and before packing;
an atomic output publication that has completed is not rolled back.

Targets are publisher declarations. Packing verifies transport/container integrity without
loading classes, executing providers or checking the selected host's SDK/ABI. Native import
performs those checks. A layer may patch instances from an earlier Bundle and need not compile
alone. Likewise, an embedded code dependency can be supplied by another selected Bundle; the
combined dependency graph is validated at import. No network dependency download is performed.

Authors own the values in the published definition. To export an existing committed Profile,
use its host-reviewed portable export instead of copying local runtime data into a Bundle.
This writer does not bypass feature export rules or publish local credentials, settings,
history or workspace data on behalf of a Profile.

## Importing the output

Select the archive with the Profile screen's Bundle action under a new Profile ID, or call
SDK 73 `importBundles` with its path/digest and the current catalogue revision. Native import
verifies the complete selected Bundle stack and stages code, then creates an isolated draft.
Preview and activation remain explicit. Subsequent drafts can change Bundle order.

## Evidence and remaining work

Writer tests cover deterministic output across code ordering, generated identities/addresses,
definition round trips, invalid digest/duplicate/version refusal, unchanged output and protection
of input paths. CLI tests cover relative paths and data-only Bundles, and the actual Gradle
task has produced an independently inspected archive.

Real Desktop JAR and physical Android APK cases use the writer to publish archives, import them
through Profile management, remove input/outer deployments, restart from frozen intent and
native caches, and export through verified feature policies. These establish the publishing
API/native import boundary. They do not establish selected-file acceptance in an OS picker.
Self-contained committed Profile export and verified target-host lock rebuilding now have a
separate [archive backend](profile-archives.md); its SDK/file UI connection remains outstanding.
