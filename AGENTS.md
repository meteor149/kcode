# Repository Guidelines

## Project Structure

This Kotlin Multiplatform project uses Compose, Koog, and Cordis for Android and desktop. Product features are replaceable providers and consumers in a Cordis plugin tree. `settings.gradle.kts` is authoritative for Gradle modules; the former `shared` module has been consolidated into the SDK and feature providers.

- `apps/androidApp` and `apps/desktopApp` are native hosts. `plugins/platform-android` and `plugins/platform-desktop` adapt platform loading and host primitives; `plugins/bundle-native` selects the default product composition.
- `plugins/api/src/commonMain` owns neutral domain state, persistence and agent contracts, plugin management, and open UI contribution contracts. `plugins/runtime` owns composition, lifecycle, and committed snapshots. `plugins/installation-store` persists installed packages and enable states.
- Model vendor plugins are independent modules under `plugins/llm`; each owns its client dependencies, model catalog, connection metadata and labels. `plugins/llm-core` owns common registry/configuration and optional standard UI. Both settings and conversation options consume the committed provider catalog.
- `libraries/ui` is an independent reusable component, design, and icon library. `plugins/default-ui-api` is the optional SDK for default layouts, pages, navigation, settings sections, and presenters. `plugins/application` coordinates the root; `plugins/ui-pages`, `plugins/ui-messages`, `plugins/ui-shell`, `plugins/ui-theme`, and feature-owned contributions implement the shipped interface. Registry implementations belong to `ui-contributions` and `default-ui-bridge`, not SDK modules.

Keep shared code in `commonMain` and platform implementations in the narrowest source set. Read [plugin architecture](docs/plugin-architecture.md) and [plugin development](docs/plugin-development.md) before changing composition or public contracts.

## Plugin Contracts and Lifecycle

- Consumers depend on `plugins/api` contracts and declare required Cordis services with `inject`; do not import concrete providers or create product singletons in host factories. Default UI extensions may also depend on `plugins/default-ui-api` and opt into `libraries/ui`.
- Allocate provider resources during plugin `apply`, validate deployable configuration with `ConfigValidator`, and collect registration disposers in the owning effect/Fiber. Missing required services suspend consumers until providers return.
- Registrations need stable, nonempty IDs and independent, idempotent disposers. Old cleanup must never remove a newer registration. Invoke provider callbacks outside registry locks; stale callbacks must reject work after withdrawal.
- Own asynchronous operations explicitly, using `PluginOperationOwner` where appropriate. Withdrawal cancels and joins owned work before releasing resources. Distinguish borrowed from owned resources, clean up partial allocations, and attempt all releases even when one fails. Callbacks must not unload their own provider or close their runtime.
- Install, replace, enable, disable, and uninstall through `AgentPluginManager`; do not bypass composition persistence through the Loader. Finish or cancel active agent turns before changing the plugin composition. Failed preparation or manifest publication must restore the committed composition.
- External desktop JARs and Android APK/dex packages share only exported SDK/framework identities from `PluginHostApiPackages`; implementations and private dependencies remain independently loaded. External resources come from their verified deployment graph, never a host APK fallback.
- `CurrentPluginApiVersion` in `AgentPluginManager.kt` is the ABI authority (currently 77); native package manifests declare a supported API range (currently 76–77). Current-API packages require the host ABI fingerprint; older packages in a declared range use their fingerprint as immutable build/lock identity, and publishers must validate compatibility for every claimed API. Public ABI changes require reviewing shared exports, versioning, package tests, and documentation; a matching version alone does not prove compiler or binary compatibility.
- Harness APIs marked reserved in [the reserved API guide](docs/harness-reserved-api.md) have no providers. Do not publish placeholder services that claim unsupported capabilities.

## Build and Test Commands

- `./gradlew :apps:desktopApp:run` — run the desktop app.
- `./gradlew :apps:androidApp:installDebug` — install Android debug output.
- `./gradlew :plugins:api:allTests` — test the plugin SDK.
- `./gradlew :plugins:default-ui-api:desktopTest` — test default UI contracts.
- `./gradlew :plugins:platform-desktop:test` — test desktop composition and real external JAR loading.
- `./gradlew :plugins:platform-android:connectedDebugAndroidTest` — test external APK/dex loading on an API 35+ device or emulator.
- `./gradlew allTests` — run all available multiplatform tests.
- `./gradlew allTests :apps:desktopApp:compileKotlin :apps:androidApp:assembleDebug` — validate shared changes and both hosts.

Use `gradlew.bat` on Windows and JDK 21; generated bytecode targets Java 17.

## Kotlin and UI Conventions

Use four-space indentation, multiline trailing commas, explicit imports, `PascalCase` for types/composables, and `camelCase` for functions and properties. Format with IntelliJ/Android Studio defaults.

- `libraries/ui/.../ui/design` owns spacing, sizing, typography, shapes, colors, and the single `KcodeTheme` entry. Prefer `MaterialTheme` semantic roles; never introduce page-local palettes or duplicate tokens.
- `libraries/ui/.../ui/component` contains reusable, page-agnostic UI shared by plugins. The library must not depend on shared, plugin contracts, repositories, or application localization; callers supply labels and callbacks. Prefer stateless APIs, slots, and event callbacks; components must not navigate or access repositories/services directly.
- Store control icons as 24×24 VectorDrawable XML in `libraries/ui/src/commonMain/composeResources/drawable` and render them through `KcodeIcon` with semantic tint. Do not use text glyphs, page-local Canvas drawings, SVG resources, or low-resolution bitmaps for UI controls; raster files are limited to brand and platform launcher artwork with appropriate density variants.
- `plugins/ui-shell` owns default layout/settings pages, `plugins/ui-pages` owns conversation pages and their frame preparation, `plugins/ui-messages` owns message/transcript rendering, and `plugins/ui-theme` owns the default theme; settings forms and their validation belong to the owning feature plugin, which registers optional settings contributions as children. Keep page-specific components nearby, but move reusable UI to `libraries/ui`, shared session state to SDK `ui/state`, and non-UI behavior to its domain package.
- The kernel uses open `UiSlotKey` contributions. Use `KcodeUiContributions.snapshot(key)` for a specific projection; default UI consumers use typed `KcodeUiSlots.resolve(key)` during frame preparation. Retain prepared values rather than live service lookups in composition. Keys with the same contract ID must agree on their value type.
- `ApplicationRenderer` prepares an `ApplicationFrame` using a short-lived `ApplicationServices` lookup. Each root declares its own dependencies; native hosts must not impose default layouts, themes, or services on alternative roots.
- Keep shared UI in `commonMain`, extract hard-coded text to localization resources, and isolate platform APIs behind narrow interfaces or `expect`/`actual` implementations.

## Persistence Conventions

Access persistence through common contracts and repositories, never from UI. Use `AppSettingsStore` for settings. Android uses encrypted MMKV for small preference documents. `StoredAppSettings` carries opaque feature namespaces and legacy migration values; schemas/defaults belong to features. Preserve historical keys as read-only migration inputs and update versioned snapshot migration with envelope changes.

Repository providers own allocation, generic persistence codecs, and closure; feature providers own configuration defaults. Resource closure must preserve durable data; replacing a provider does not migrate its data automatically. Settings forms submit changes through the settings command/session boundary instead of mutating active execution settings directly. ADB configuration uses the same commands and requires a started application runtime; follow [the ADB settings guide](docs/adb-settings.md) and verify `result=-1` rather than only the adb exit code.

Treat model provider IDs as extensible identities. Built-in catalogs are not allowlists, and unloading a provider must not discard its saved ID or credentials. Localization providers own dictionaries and fallback policy; neutral contracts share text identities rather than product strings.

Use Room 3/SQLite for structured, queryable data such as conversations. Keep DAO calls suspending, wrap multi-table mutations in transactions, export schemas, and provide an explicit migration when the schema version changes. Platform database builders belong in platform source sets.

## Tests and Contributions

Tests use `kotlin.test`. Put shared tests in `commonTest`, desktop tests in `desktopTest`, Android unit tests in `androidUnitTest`, and instrumentation tests in `androidInstrumentedTest`. Name classes `*Test.kt` and test observable behavior.

For lifecycle changes, cover cancellation, stale references, withdrawal/recovery, replacement, and failed persistence commits. Use actual JAR/APK packages to verify shared contract identity, private implementation identity, and resource independence. Unit tests and app-UID instrumentation do not establish real Shizuku authorization or root execution; record evidence limits in [the verification guide](docs/verification.md).

Keep `README.md` and `README.zh-CN.md` aligned when changing project structure, supported behavior, or build commands. Maintain topic guides under `docs` in English, link them through [docs/README.md](docs/README.md), and document plugin entry points/configuration in module READMEs.

Use concise Conventional Commit messages, for example `fix: prevent selection focus crash`. Pull requests should describe behavior changes, list tested targets, link issues, and include before/after media for UI work. Never commit API keys, `local.properties`, generated databases, or device identifiers.
