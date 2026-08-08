# Repository Guidelines

## Project Structure & Module Organization

This is a Kotlin Multiplatform project built with Compose Multiplatform and Koog. `shared` is the unified KMP module: `commonMain` contains UI, domain logic, Room persistence, and web search; `agentMain` contains the shared Koog runtime; `jvmAndAndroidMain` contains DataStore; and platform implementations live in `androidMain`, `desktopMain`, `iosMain`, and `wasmJsMain`. Application hosts are under `apps/androidApp` and `apps/iosApp`. The browser SQLite worker is isolated at `apps/web/sqliteWasmWorker`, and the local H5 runtime remains an independent extension at `extensions/h5Container`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper with JDK 21 (the application targets Java 17 bytecode):

- `./gradlew :shared:run` — run the desktop application.
- `./gradlew :apps:androidApp:installDebug` — build and install Android debug output on a connected API 35+ device.
- `./gradlew :shared:wasmJsBrowserDevelopmentRun` — start the browser development build.
- `./gradlew :shared:allTests` — run shared and platform unit tests for the main module.
- `./gradlew allTests` — run all available multiplatform tests across modules.
- `./gradlew :apps:androidApp:assembleDebug` — produce an Android debug APK without installing it.

On Windows, use `gradlew.bat` in place of `./gradlew` when needed.

## Coding Style & Naming Conventions

Follow standard Kotlin style: four-space indentation, trailing commas in multiline declarations, and explicit imports. Use `PascalCase` for types and composables, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Keep platform-specific code in the narrowest applicable source set; avoid leaking Android APIs into `commonMain`. Prefer small composables, immutable state, and localized strings over hard-coded UI text. No repository-specific formatter is configured, so format with IntelliJ/Android Studio Kotlin defaults.

## Testing Guidelines

Tests use `kotlin.test`; Android instrumentation tests live under `src/androidTest`. Name test classes `*Test.kt` and describe observable behavior in test methods. Add shared logic tests to `commonTest`, JVM-specific tests to `desktopTest`, and Android-only tests to `androidUnitTest` or `androidTest`. Run the affected module tests plus `./gradlew allTests` before submitting.

## Commit & Pull Request Guidelines

Git history is not included in this checkout. Use concise, imperative commits, preferably Conventional Commit prefixes such as `fix: prevent selection focus crash` or `feat: add web search provider`. Pull requests should explain the behavior change, list tested targets and commands, link relevant issues, and include before/after screenshots or recordings for UI changes. Keep unrelated refactors separate.

## Security & Configuration

Never commit API keys, `local.properties`, device identifiers, or generated databases. Preserve platform secret-storage boundaries (Android Keystore and iOS Keychain), and treat browser-stored credentials as development-only.
