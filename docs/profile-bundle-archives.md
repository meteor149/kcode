# External Profile Bundle archives

`ProfileBundleArchive` prepares ordered external Bundle archives without mounting a plugin.
It is a native implementation API in `plugins/profiles`, shared by Desktop and Android.
The extension `.kbundle` is a distribution convention; validation uses contents and SHA-256.
SDK 73 exposes `ProfileManagementClient.importBundles(ProfileBundleImport(...))` through
the host and the owned Cordis bridge. The optional Profile screen has a separate Bundle
archive action; its JSON import/export actions retain their document format. A shipping
archive builder is available through [publishing tooling](profile-bundle-publishing.md);
self-contained committed Profile archive export remains outstanding.

## Format

The archive uses the existing Cordis package container and `PluginPackageArchive` validation.
Its root `plugin.json` identifies the Bundle with a semantic version and declares one
pure-data variant. The variant's runtime ID is `kcode-profile-bundle`, minimum runtime
version is `1`, and both its entry point and artifact are `bundle.json`. Runtime metadata
and variant extensions are empty. Targets declare the supported native hosts.

The only root extension is `ai.meteor.kcode.profile-bundle`:

```json
{
  "formatVersion": 1,
  "packages": [
    { "id": "example.code", "path": "code/<archive-sha256>.bin" }
  ]
}
```

`bundle.json` encodes the shared SDK `ProfileBundle`. Its ID/version must equal the root
manifest identity, and its format version must be 1. Definition reads are bounded to
2 MiB with strict UTF-8 decoding. Root dependencies list the exact ID/version of every
embedded code package; the metadata package IDs must be unique and match that list.
The manifest file list contains exactly `bundle.json` and the declared code paths.
Code addresses must match each file record's SHA-256. Embedded `.bin` files contain
ordinary native `.kplugin` archives; the suffix avoids nested reserved archive filenames
in the generic container. Outer files, paths, limits and digests use Cordis validation.

Bundles with no embedded code are permitted. They can provide data-only operations for
modules supplied by another input Bundle. Preparation currently supplies no external offer
catalogue, so selected code must be satisfied by the combined embedded releases.

## Preparation and publication

The host supplies its package directory, native `PackageHost`, and native package resolver:

1. Pass a single archive/digest or an ordered list of `ProfileBundleArchiveInput` values.
2. Verify/deploy the outer containers into `bundles` beneath the package directory.
3. Decode frozen Bundle definitions and merge embedded code declarations. Repeated package
   IDs may share the same archive digest and version; conflicting releases are rejected.
4. Compile the complete ordered layer stack using the ordinary Profile compiler. Duplicate
   Bundle references and invalid structure reject preparation before native code staging.
5. Resolve all embedded code with the native verifier, including platform, SDK/ABI, actual
   package identity and dependency graph. Verify the result against declared versions/digests.
6. Resolve the selected composition and return a validated `PortableProfileDocument` with
   frozen definitions and its current-host package lock.

Preparation may populate immutable caches, but never allocates product providers, creates
a Profile draft or changes catalogue authority. The caller publishes the returned document
through revision-checked `importPortable` under a new Profile ID. Import creates an isolated
draft; activation remains an independent host-owned command. Cancellation propagates through
interruptible archive I/O and subsequent suspending resolver operations.

Later Bundle layers may configure instances inserted by earlier layers. All inputs can share
one code release, and the resulting lock retains only code needed by the resolved composition
and its dependency closure. There is no fake Bundle plugin or Bundle entry in that code lock.

## Native import command and file selection

`ProfileBundleImport` carries an ordered list of `ProfileBundleArchiveReference` values
(temporary local archive path and SHA-256), a new Profile ID/name and the expected catalogue
revision. These local addresses are input locators only and never enter portable documents.
Callers retain input files until import returns. Requests accept one to sixteen archives.
The host checks revision and create-only identity before staging and again at publication.
A conflict after staging can leave cache content, but publishes no draft. The command is
available in Ready and RecoveryRequired and never activates the imported Profile. Bridge
withdrawal cancels/joins admitted metadata operations and rejects stale clients.

The native action captures revision before opening the picker. Desktop uses a multiple-file
AWT dialog; Android uses `OpenMultipleDocuments`. Picker-returned order becomes Bundle
order and can be edited later in the draft. Files stream into owned temporary archives while
computing hashes, with a 512 MiB limit across the complete selection. The command receives
only staged paths. Temporary files are removed after success, rejection or cancellation.
Android keeps cancelled request slots until the OS callback returns, preventing an old
callback from completing a new selection. Provider I/O may delay cancellation until the
provider responds. Picker cancellation changes no metadata; dirty editors must save/discard.

## Restart and exchange limits

For a first imported draft, native preparation locates required releases by locked SHA-256
in its package cache, overriding newer offers with the same package ID. A cache locator does
not authorize bytes: the resolver revalidates the actual archive and ordinary lock checks still
apply. Committed and historical generations retain their existing restoration semantics.

After successful preparation/import, original archive paths and the outer Bundle deployment
are unnecessary. Frozen definitions live in the draft/generation and verified code remains
in the native package cache. Deleting that code cache can make activation fail; the implementation
does not download missing releases or recover them from host application resources.

Portable JSON export retains frozen Bundle/code intent and applies the generation's verified
feature export rules. It does not embed code archives. A prepared lock is native-host specific;
cross-platform exact-lock adaptation and a self-contained exported archive are not implemented
by this preparation API.

## Verification

The real Desktop JAR case prepares two ordered Bundles sharing Localization code, observes
the second layer's dictionary configuration, imports the frozen document, deletes input and
outer Bundle files, starts twice and exports through the host's verified feature schema.
It imports through the native host SDK client without changing the active bootstrap Profile.
It also rejects an incorrect outer digest, mismatched code version, unknown metadata version
and duplicate Bundle references without publishing a Profile.

The real Android APK case prepares/imports a single Bundle and starts twice after removing
the source archive and outer Bundle deployment. Native host API tests alone do not prove an
OS picker acceptance round trip or self-contained binary archive export.
