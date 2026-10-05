# Durable plugin composition provider

`provider.plugin-installations.platform` provides `KcodePluginInstallations` with a `PluginCompositionStore`. Native hosts use `FilePluginCompositionStore` in the same app-private directory accepted by their artifact loader. No artifact download or publisher trust decision is made here.

Supplying `KcodePluginRuntimeConfig.pluginCompositionStore` mounts this managed provider even
when the profile excludes default product plugins. An explicitly supplied installation provider
takes precedence. Persistence infrastructure does not require a default UI, agent, or tool set.

Snapshot format 1 atomically records builtin enable states, external descriptors, SHA-256,
artifact dependencies, declared host API, portable configuration, and optional cross-platform
package locks (archive digest/path, selected variant, SDK ABI and exact package dependencies).
The bundled release history stores the last offered archive hash independently of installation
presence, allowing upgrades while remembering user replacement and uninstall decisions.
Startup restores dependencies first and revalidates packages
through the native package provider/controller. Unknown formats, invalid graphs,
corrupted/oversized files and incompatible APIs fail restoration without rewriting the file.
Loading validates historical descriptor structure first. A tracked bundled release can be
replaced by a newly verified compatible release before mounting; an incompatible user
installation still fails restoration. Saving always requires the current API.

Publication writes a bounded temporary file, syncs it and atomically renames it. Runtime view publication follows successful manifest publication. If saving fails, the runtime remounts the previous plugin composition; this restores registrations and contracts, not arbitrary mutable provider instance state. Config must be Unit, null, JSON or a supported scalar and must not contain plaintext credentials. Disabling this optional provider makes future management process-local; the last saved state will still apply at the next normal boot.
