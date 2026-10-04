# Durable plugin composition provider

`provider.plugin-installations.platform` provides `KcodePluginInstallations` with a `PluginCompositionStore`. Native hosts use `FilePluginCompositionStore` in the same app-private directory accepted by their artifact loader. No artifact download or publisher trust decision is made here.

The manifest atomically records builtin enable states and external package descriptors, SHA-256, dependency graph, declared host API version, enable state and portable config. Startup restores dependencies first and revalidates every package through the native controller. Unknown manifest versions, invalid dependency graphs, corrupted/oversized files and incompatible API versions fail restoration without rewriting the file.

Publication writes a bounded temporary file, syncs it and atomically renames it. Runtime view publication follows successful manifest publication. If saving fails, the runtime remounts the previous plugin composition; this restores registrations and contracts, not arbitrary mutable provider instance state. Config must be Unit, null, JSON or a supported scalar and must not contain plaintext credentials. Disabling this optional provider makes future management process-local; the last saved state will still apply at the next normal boot.
