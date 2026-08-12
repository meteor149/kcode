<div align="center">
  <img src="branding/kcode-mark-transparent.png" alt="kcode logo" width="112" />
  <h1>kcode</h1>
  <p><strong>An elegant, full-featured, cross-platform native AI agent.</strong></p>
  <p>One adaptive Compose UI. One capable agent runtime. Your models, tools, skills, and data.</p>

  <p>
    <strong>English</strong> · <a href="docs/README.zh-CN.md">简体中文</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.3.21" />
    <img src="https://img.shields.io/badge/Compose_Multiplatform-1.8.2-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose Multiplatform 1.8.2" />
    <img src="https://img.shields.io/badge/Koog-1.1.1-8FD694" alt="Koog 1.1.1" />
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-3DA639" alt="Apache License 2.0" /></a>
  </p>
</div>

> [!IMPORTANT]
> kcode is evolving quickly. Expect breaking changes, verify important agent actions, and read the security model before enabling privileged tools.

## The idea

kcode is an open-source native AI agent for Android, iOS, desktop, Web, and HarmonyOS. It is built around a simple belief: an agent should feel like a thoughtfully designed application—not a terminal transplanted into a chat box, and not a thin web wrapper duplicated for every device.

The project combines an adaptive [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) interface with a [Koog](https://docs.koog.ai/)-powered runtime, local-first persistence, real tools, reusable Skills, persistent Goals, multi-agent orchestration, and runnable Web Artifacts. The same conversation can therefore move naturally from an answer, to tool-backed work, to a longer autonomous objective, to a small application you can open and use.

## What it can do

### Work as an agent, not only a chatbot

- Stream rich Markdown while preserving assistant responses and tool-call history in the conversation.
- Expose tool progress and results in place, with stop, regenerate, message selection, and rendered conversation export on supported platforms.
- Keep generation alive when the UI moves into the background on supported mobile hosts.
- Search the current Web through Google, Exa, or Bright Data and return source links.
- Read, list, write, and patch workspace files; read media where the platform implementation supports it.
- Run shell commands inside the desktop workspace, or on Android as the app UID, through Shizuku, or as root.
- Route capability-bearing tools through a global `Deny`, `Ask`, or `Bypass` permission policy; internal coordination and Goal bookkeeping remain automatic.

### Plan and finish longer work

**Goals** turn a conversation into a persistent objective. A Goal survives app restarts, tracks status, elapsed time, and optional token budget, and can continue across agent turns until it is completed or genuinely blocked. Create one with `/goal <objective>`, then pause, resume, edit, or clear it from the conversation. Active Goals are surfaced above the composer; completed Goals leave the UI automatically.

**Multi-agent orchestration** lets the root agent delegate concrete subtasks, exchange messages, interrupt or reuse workers, and wait for their results before producing the final response. kcode supports five concurrent agents including the root coordinator. Running workers appear above the composer in a live two-column status area; selecting one opens its activity and output.

### Extend behavior with Skills

kcode discovers `SKILL.md` packages from `/workspace/.agents/skills` and `/workspace/.kcode/skills`, then injects only the relevant instructions for the current request. Skills can describe domain knowledge, repeatable workflows, and tool usage without bloating the permanent system prompt. The runtime validates package boundaries and supports additional Skill providers through its authority-based provider model.

A built-in `kcode-web-app-builder` Skill drives the complete Web application loop: implement a responsive app, open it in the real container, inspect and interact with the rendered UI, collect console output and screenshots, fix defects, and—only after explicit confirmation—save it as an Artifact.

### Build and keep runnable Artifacts

Web Artifacts are small local applications managed inside the agent workspace. The agent can build one from conversation, debug it against the same Web container used by the product, and save it into the Artifacts library. Saved apps launch like native app entries instead of disappearing into chat history.

The Web container supports local apps and remote sites, foreground/background lifecycle, a floating dock for active containers, DOM inspection, safe interaction handles, console collection, screenshots, and responsive debugging. Android and iOS additionally bridge available Web APIs to native device capabilities such as location, motion sensors, vibration, battery, camera, microphone, and file picking while preserving system permission checks. See [Artifact storage](docs/artifacts.md) and the [Web container guide](extensions/webContainer/README.md) for the implementation contracts.

### Bring the model you prefer

kcode currently integrates:

- OpenAI and Azure OpenAI
- Anthropic
- Google Gemini
- DeepSeek
- OpenRouter
- Amazon Bedrock (desktop)
- Mistral AI
- Alibaba DashScope / Qwen
- Ollama
- Zhipu GLM

Providers, models, endpoints, regions, credentials, and temperature are configured in the app. Ollama can connect to a local endpoint without an API key.

## Platform support

| Capability | Android | iOS | Desktop | Web | HarmonyOS |
| --- | :---: | :---: | :---: | :---: | :---: |
| Adaptive native Compose UI | ✅ | ✅ | ✅ | ✅ | ✅ |
| Model chat and local history | ✅ | ✅ | ✅ | ✅ | ✅ |
| Streaming Koog agent runtime | ✅ | ✅ | ✅ | ✅ | — |
| Persistent Goals | ✅ | ✅ | ✅ | ✅ | Manual |
| Multi-agent orchestration and Skills | ✅ | ✅ | ✅ | ✅ | — |
| Agent file workspace and Web search | ✅ | ✅ | ✅ | ✅ | — |
| Web Artifacts and container | ✅ | ✅ | ✅ | ✅ | — |
| Conversation image export | ✅ | — | ✅ | — | — |
| Native mobile Web capability bridge | ✅ | ✅ | — | Browser APIs | — |
| Shell tool | App UID / Shizuku / root | — | `/workspace` | — | — |
| Amazon Bedrock client | — | — | ✅ | — | — |

HarmonyOS currently ships through an isolated Kotlin/Native + ArkTS host and provides the shared UI, provider-backed chat, settings, and local conversation persistence. The full Koog agent runtime is not connected there yet. Availability on every platform also depends on the selected model, device or browser capabilities, and granted permissions; direct browser-to-provider requests are subject to CORS.

## Get kcode

Tagged builds publish a signed Android APK, Windows MSI, macOS DMG, Linux DEB, and a Web distribution to [GitHub Releases](https://github.com/meteor149/kcode/releases). iOS and HarmonyOS currently need to be built from source.

### Requirements

- JDK 21; JVM bytecode targets Java 17
- Android Studio and Android SDK 35 for Android (minimum Android API 35)
- macOS, Xcode, and [XcodeGen](https://github.com/yonaskolb/XcodeGen) for iOS
- A modern browser for the Wasm target
- DevEco Studio and the HarmonyOS toolchain for HarmonyOS

Use the checked-in Gradle wrapper from the repository root. On Windows, replace `./gradlew` with `gradlew.bat`.

### Desktop

```bash
./gradlew :shared:run
```

### Android

Start an API 35+ emulator or connect a compatible device, then run:

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

### HarmonyOS

The HarmonyOS build uses a standalone Gradle project so its Kotlin/Compose fork remains isolated from the main toolchain. On Windows, publish both native ABIs first:

```powershell
.\gradlew.bat -p apps\harmonyApp\kotlin publishDebugBinariesToHarmonyApp
```

Then open `apps/harmonyApp` in DevEco Studio or run its Hvigor `assembleHap` task. See the [HarmonyOS build notes](apps/harmonyApp/README.md) for details.

## First run

1. Open **Settings → Model provider** and select a service.
2. Enter its credentials and any required endpoint, deployment, or region.
3. Return to the conversation and choose a model and temperature from the composer.
4. Ask a normal question, request tool-backed work, explicitly ask the agent to delegate parallel subtasks, or create a persistent objective with `/goal <objective>`.

Credential storage is platform-specific:

- Android encrypts settings with MMKV and protects its key with Android Keystore.
- iOS encrypts settings with MMKV and protects its key with Keychain.
- Desktop stores settings in the application data directory; native desktop keychain integration is planned.
- Web uses browser storage. Use a trusted origin and prefer a server-side model gateway for production.
- HarmonyOS stores application settings in the app's private data directory.

## Security model

- Desktop, iOS, and Web expose a virtual `/workspace` rooted in application-owned storage. Path traversal and symbolic-link escape are rejected. Skill and Artifact resources are also constrained to their packages or managed workspace trees.
- Android file and media tools may accept real absolute paths in addition to the private workspace, but remain subject to Android/Linux filesystem permissions and the selected execution identity.
- The global tool permission gate controls whether kcode denies, confirms, or immediately runs a tool. `Bypass` skips only kcode's prompt; it never bypasses operating-system, browser, WebView, Keychain, Keystore, Shizuku, or root-manager controls.
- Android shell execution has explicit app UID, Shizuku/ADB-shell, and root modes. An unavailable privilege source fails instead of silently falling back to another identity.
- Local Web apps run in isolated containers and request sensitive capabilities at runtime. Remote sites never receive kcode's local native fallback bridge.
- Artifact saving requires explicit user confirmation and uses validated, bounded, rollback-aware storage operations.
- Never commit API keys, `local.properties`, device captures, generated databases, or other private data.

Please report security-sensitive issues privately to the maintainers instead of publishing exploit details in a public issue.

## Architecture

```text
apps/
  androidApp/          Android application host
  iosApp/              SwiftUI host for the shared framework
  harmonyApp/          ArkTS host + isolated Kotlin/Native Compose build
  web/
    sqliteWasmWorker/  SQLite Wasm worker and OPFS bridge
shared/
  src/commonMain/      Adaptive UI, domain state, persistence contracts
  src/agentMain/       Koog runtime, tools, Goals, Skills, and multi-agent orchestration
  src/*Main/           Platform storage, networking, tools, and host integrations
  schemas/             Room migration schemas
extensions/
  webContainer/        Isolated Web runtimes, lifecycle, debugging, and native bridges
docs/                  Design and engineering documentation
```

Android, iOS, and desktop use the same Room schema with bundled SQLite. Web preserves that schema through a worker-backed SQLite database in OPFS. HarmonyOS currently uses private JSON persistence while sharing the common application and UI sources through its standalone build.

## Build and test

```bash
# Shared multiplatform tests
./gradlew :shared:allTests

# Every available multiplatform test suite
./gradlew allTests

# Android debug APK
./gradlew :apps:androidApp:assembleDebug

# Production Web bundle
./gradlew :shared:wasmJsBrowserProductionWebpack
```

Desktop installers are available through `packageMsi`, `packageDmg`, and `packageDeb` tasks under `:shared`. Tagged commits automatically package Android, desktop, and Web release assets.

## Contributing

Contributions are welcome. Read [AGENTS.md](AGENTS.md) for repository structure, conventions, test commands, and pull-request expectations. Keep changes focused, test observable behavior, and include before/after media for UI work.

The long-term direction is deliberate: preserve a quiet, polished native experience while expanding the agent's autonomy, tools, portability, and user control. Features should feel integrated into the product—not bolted onto the conversation.

## License

Copyright 2026 The kcode Authors.

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify, and distribute this project, including for commercial purposes, subject to the license terms. The license includes an express patent grant and requires preservation of applicable copyright, license, and NOTICE information. Third-party components remain under their respective licenses. Use of the kcode name and logo is governed by the trademark provisions in Section 6 of Apache-2.0.

See [NOTICE](NOTICE) for attribution information.
