<div align="center">
  <img src="branding/kcode-mark-transparent.png" alt="kcode logo" width="112" />
  <h1>kcode</h1>
  <p><strong>A calm, local-first AI workspace for every screen.</strong></p>
  <p>One Compose Multiplatform UI. One Koog agent runtime. Your models, tools, and data.</p>

  <p>
    <strong>English</strong> · <a href="docs/README.zh-CN.md">简体中文</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.3.21" />
    <img src="https://img.shields.io/badge/Compose_Multiplatform-1.8.2-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose Multiplatform 1.8.2" />
    <img src="https://img.shields.io/badge/Koog-1.0.0-8FD694" alt="Koog 1.0.0" />
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-3DA639" alt="Apache License 2.0" /></a>
  </p>
</div>

> [!IMPORTANT]
> kcode is under active development. Build it from source, expect breaking changes, and review the security notes before enabling privileged tools.

## What is kcode?

kcode is an open-source, cross-platform AI chat and agent application built with [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) and [Koog](https://docs.koog.ai/). It combines a focused chat experience with streaming Markdown, persistent history, visible tool execution, local workspaces, web search, and sandboxed Web containers—without maintaining a separate UI for every platform.

## Highlights

- **One UI everywhere** — shared adaptive Compose UI for Android, iOS, desktop, and Web.
- **Agent-native conversations** — real-time streaming, Markdown rendering, tool-call progress, stop/regenerate, message selection, and rendered long-image export on supported platforms.
- **Bring your own model** — OpenAI, Azure OpenAI, Anthropic, Google Gemini, DeepSeek, OpenRouter, Amazon Bedrock, Mistral AI, Alibaba DashScope, Ollama, and Zhipu GLM.
- **Useful built-in tools** — bounded `/workspace` file operations, Google/Exa/Bright Data web search, and Web containers for local apps or remote sites.
- **Mobile capability bridge** — local Web apps can request camera, location, compass, motion sensors, vibration, battery, network, and other platform-reported capabilities.
- **Explicit permission gate** — globally choose `Deny`, `Ask`, or `Bypass` for tool calls. Operating-system permissions still apply.
- **Local-first persistence** — versioned settings and Room/SQLite conversation history with platform-native storage boundaries.

## Platform support

| Capability | Android | iOS | Desktop | Web |
| --- | :---: | :---: | :---: | :---: |
| Shared Compose UI and Koog agent | ✅ | ✅ | ✅ | ✅ |
| Streaming Markdown and persistent history | ✅ | ✅ | ✅ | ✅ |
| Sandboxed file workspace and web search | ✅ | ✅ | ✅ | ✅ |
| Web container | ✅ | ✅ | ✅ | ✅ |
| Rendered conversation image export | ✅ | — | ✅ | — |
| Mobile hardware bridge | ✅ | ✅ | — | Browser APIs |
| Shell tool | App UID / Shizuku / root | — | — | — |
| Amazon Bedrock client | — | — | ✅ | — |

Availability also depends on the device, browser, model provider, and granted permissions. Browser-side provider calls are subject to CORS.

## Quick start

### Requirements

- JDK 21 (the JVM bytecode target remains Java 17)
- Android Studio and Android SDK 35 for Android
- macOS, Xcode, and [XcodeGen](https://github.com/yonaskolb/XcodeGen) for iOS
- A modern browser for the Wasm target

Use the checked-in Gradle wrapper from the repository root. On Windows, replace `./gradlew` with `gradlew.bat`.

### Desktop

```bash
./gradlew :shared:run
```

### Android

Start an API 35+ emulator or connect a device, then run:

```bash
./gradlew :apps:androidApp:installDebug
```

### Web

```bash
./gradlew :shared:wasmJsBrowserDevelopmentRun
```

### iOS

```bash
cd apps/iosApp
xcodegen generate
open iosApp.xcodeproj
```

The Xcode target builds and embeds the shared `KcodeShared` framework. The deployment target is iOS 14.

## Configure a provider

Open **Settings → Model providers**, select a provider, and enter its credentials. Choose the active model and generation options from the conversation composer. Ollama can use a local endpoint without an API key.

Credential handling differs by platform:

- Android encrypts API keys with Android Keystore.
- iOS stores secrets in Keychain.
- Desktop currently stores configuration under `~/.kcode`; desktop secret-store integration is still planned.
- Web stores configuration in `localStorage`; use only on a trusted origin and prefer a server-side gateway for production deployments.

## Security model

- Agent file tools are confined to a virtual `/workspace`; traversal and symbolic-link escapes are rejected and size limits are enforced.
- Tool calls pass through the global permission gate. `Bypass` skips the kcode confirmation only—it does not bypass Android/iOS/browser permissions.
- Android shell execution has explicit identity modes and bounded time/output. Shizuku and root are opt-in external privilege sources.
- Web apps run in an isolated container, query capability availability at runtime, and request approval for sensitive access.
- Never commit API keys, `local.properties`, device captures, or generated databases.

Please report security-sensitive issues privately to the maintainers instead of publishing exploit details in a public issue.

## Architecture

```text
apps/
  androidApp/       Android application host
  iosApp/           Lightweight SwiftUI host
  web/
    sqliteWasmWorker/ SQLite Wasm worker module and OPFS bridge
shared/             Unified Compose Multiplatform shared module
  src/commonMain/   UI, state, Room schema, search, and service contracts
  src/agentMain/    Koog runtime and cross-platform agent tools
  src/*Main/        Android, iOS, desktop, and Web implementations
  schemas/          Room migration schemas
extensions/
  webContainer/      Isolated Web container runtimes and capability bridges
docs/               Design and engineering documentation
```

Android/iOS/desktop use the same Room schema with bundled SQLite. Web keeps the same schema through a worker-backed SQLite database in OPFS.

## Build and test

```bash
# Main multiplatform tests
./gradlew :shared:allTests

# All available module tests
./gradlew allTests

# Android debug APK
./gradlew :apps:androidApp:assembleDebug

# Production-style Web bundle
./gradlew :shared:wasmJsBrowserProductionWebpack
```

## Contributing

Contributions are welcome. Read [AGENTS.md](AGENTS.md) for repository structure, conventions, test commands, and pull-request expectations. Keep changes focused, add tests for observable behavior, and include screenshots or recordings for UI changes.

## License

Copyright 2026 The kcode Authors.

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify, and distribute this project, including for commercial purposes, subject to the license terms. The license includes an express patent grant and requires preservation of applicable copyright, license, and NOTICE information. Third-party components remain under their respective licenses. Use of the kcode name and logo is governed by the trademark provisions in Section 6 of Apache-2.0.

See [NOTICE](NOTICE) for attribution information.
