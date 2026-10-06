# Native plugin package format

Kcode packages use the Cordis `.kplugin` archive format. The root manifest identifies one
versioned package, declares exact package dependencies, and carries platform variants and
verified payload hashes. Each native variant has an `ai.meteor.kcode` extension containing its
compiled plugin API, declared compatible host API range, SDK ABI fingerprint and capabilities.
The package API range is the publisher's binary-compatibility claim; validate each host API in
that range before release. See [plugin development](plugin-development.md) for the compatibility
policy and SDK requirements.

## Package identity and presentation

`id` and `version` are required. Use a stable reverse-domain style ID and a SemVer version.
The following Cordis root fields are available for presentation:

- `displayName`: optional user-facing name, falling back to `id` when absent.
- `description`: optional short explanation.
- `license`: optional SPDX identifier or license label.
- `author`: optional package author display name.
- `contributors`: optional list of contributor display names.
- `homepage`: optional HTTP or HTTPS project URL.
- `repository`: optional source repository object with `type`, `url` and optional monorepo
  `directory`.
- `bugsUrl`: optional HTTP or HTTPS issue tracker URL.
- `keywords`: optional discovery terms.
- `dependencies`: exact package IDs and versions required by this release.

The first example shows the Cordis root manifest fields. `author`, `contributors`, `homepage`,
`repository`, `bugsUrl` and `keywords` require Cordis package format 2. Existing format 1
manifests remain valid. Cordis' Gradle packager emits format 2 when any of those fields is set
and retains format 1 when they are all absent. Example:

```json
{
  "formatVersion": 2,
  "id": "org.example.weather",
  "version": "1.2.0",
  "displayName": "Weather",
  "description": "Adds local weather lookup tools.",
  "license": "Apache-2.0",
  "author": "Example Maintainer",
  "contributors": ["Weather Contributor"],
  "homepage": "https://example.org/weather",
  "repository": {
    "type": "git",
    "url": "git+https://github.com/example/weather-plugin.git",
    "directory": "plugins/weather"
  },
  "bugsUrl": "https://github.com/example/weather-plugin/issues",
  "keywords": ["weather", "forecast"],
  "dependencies": [],
  "extensions": {
    "ai.meteor.kcode": {
      "configuration": { "kind": "unit" }
    }
  },
  "variants": [],
  "files": []
}
```

The example omits variant and file records for readability; a real archive must include them.
`author` is limited to 200 characters; `contributors` accepts up to 32 distinct display names,
each at most 200 characters. Homepage and bug tracker URLs use HTTP or HTTPS. Repository URLs
support HTTP(S), SSH, Git and `git+` forms; the optional directory is a safe repository-relative
path. `keywords` is an optional list of up to 32 distinct terms, each at most 64 characters.
Display name, description and license are limited to 160, 4096 and 128 characters. The Kcode
extension is reserved for Kcode configuration and variant metadata; package presentation stays
in Cordis' shared schema.

The Cordis Gradle DSL exposes these fields on each `PluginRelease`; a
`PackageRepository` value keeps repository type, URL and monorepo directory together. Kcode's
bundled package generator maps the same optional fields onto Cordis releases. Leave details
unset when publisher information is unknown rather than inventing contact or repository data.

## Variant compatibility

Each `ai.meteor.kcode` variant extension contains:

- `pluginApi`: the API version used to compile the plugin.
- `pluginApiRange`: `minimum` and `maximum` host API versions the publisher supports.
- `runtimeAbi`: SHA-256 identity of the SDK/compiler export surface used for the package.
- `capabilities`: stable service capability IDs required by the package.

The host selects a variant only when its API is within `pluginApiRange` and the compiled API
is in the host's supported range. A package compiled against the current API must match the
host's current SDK ABI fingerprint. For an older API, the immutable release lock binds the
package's ABI fingerprint to its own metadata. This lets a newer host keep loading older
packages when the publisher declares and validates that range, without republishing every
package for each host API increment.

Legacy manifests without `pluginApiRange` receive one-step forward compatibility for the
immediately previous supported API. New releases should always include the range. A matching
API number alone does not establish compiler or binary compatibility.

## Native payloads

Desktop packages carry a self-contained JAR; Android packages carry an APK. Variants declare
their target operating systems, architecture families and bitness, minimum runtime versions,
and optional system, distribution and feature constraints. The manifest records the artifact
and all payload sizes and SHA-256 hashes. The host verifies the archive, artifact, selected
variant, shared SDK identities and ABI before activation. iOS targets are metadata-only today.
