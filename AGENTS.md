# Repository Guidelines

## Project Structure & Module Organization

This is a Kotlin Multiplatform project built with Compose Multiplatform and Koog. Shared UI and domain logic live in `composeApp/src/commonMain`; the Koog runtime shared by Android, Desktop, iOS, and Web lives in `agentMain`, and the Android/Desktop DataStore implementation lives in `jvmAndAndroidMain`. Platform implementations are in `androidMain`, `desktopMain`, `iosMain`, and `wasmJsMain`. `androidApp` contains the Android launcher, while `iosApp` is the SwiftUI host. Persistence is isolated in `historyStore`, web search in `webSearch`, local H5 execution in `h5Container`, and the browser SQLite worker in `sqliteWasmWorker`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper with JDK 21 (the application targets Java 17 bytecode):

- `./gradlew :composeApp:run` — run the desktop application.
- `./gradlew :androidApp:installDebug` — build and install Android debug output on a connected API 35+ device.
- `./gradlew :composeApp:wasmJsBrowserDevelopmentRun` — start the browser development build.
- `./gradlew :composeApp:allTests` — run shared and platform unit tests for the main module.
- `./gradlew allTests` — run all available multiplatform tests across modules.
- `./gradlew :androidApp:assembleDebug` — produce an Android debug APK without installing it.

On Windows, use `gradlew.bat` in place of `./gradlew` when needed.

## Coding Style & Naming Conventions

Follow standard Kotlin style: four-space indentation, trailing commas in multiline declarations, and explicit imports. Use `PascalCase` for types and composables, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Keep platform-specific code in the narrowest applicable source set; avoid leaking Android APIs into `commonMain`. Prefer small composables, immutable state, and localized strings over hard-coded UI text. No repository-specific formatter is configured, so format with IntelliJ/Android Studio Kotlin defaults.

## Testing Guidelines

Tests use `kotlin.test`; Android instrumentation tests live under `src/androidTest`. Name test classes `*Test.kt` and describe observable behavior in test methods. Add shared logic tests to `commonTest`, JVM-specific tests to `desktopTest`, and Android-only tests to `androidUnitTest` or `androidTest`. Run the affected module tests plus `./gradlew allTests` before submitting.

## Commit & Pull Request Guidelines

Git history is not included in this checkout. Use concise, imperative commits, preferably Conventional Commit prefixes such as `fix: prevent selection focus crash` or `feat: add web search provider`. Pull requests should explain the behavior change, list tested targets and commands, link relevant issues, and include before/after screenshots or recordings for UI changes. Keep unrelated refactors separate.

## Security & Configuration

Never commit API keys, `local.properties`, device identifiers, or generated databases. Preserve platform secret-storage boundaries (Android Keystore and iOS Keychain), and treat browser-stored credentials as development-only.
