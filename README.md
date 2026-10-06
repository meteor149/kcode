<div align="center">
  <img src="branding/kcode-mark-transparent.png" alt="kcode logo" width="112" />
  <h1>kcode</h1>
  <p><strong>A Kotlin-native, plugin-first AI Harness for Android and desktop.</strong></p>
  <p>Everything is a plugin. Native by design. Your models, tools, interface, and data.</p>

  <p>
    <strong>English</strong> · <a href="README.zh-CN.md">简体中文</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.3.21" />
    <img src="https://img.shields.io/badge/Compose_Multiplatform-1.8.2-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose Multiplatform 1.8.2" />
    <img src="https://img.shields.io/badge/Koog-1.1.1-8FD694" alt="Koog 1.1.1" />
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-3DA639" alt="Apache License 2.0" /></a>
  </p>
</div>

## Meet kcode

kcode is a native AI assistant for Android and desktop, built with Kotlin Multiplatform, Compose Multiplatform, Koog, and [Cordis](https://github.com/meteor149/cordis-kotlin). It combines conversation, tool use, and a local workspace so agents can turn requests into completed tasks.

**Everything is a plugin.** Compose and replace models, tools, the agent loop, storage, and the interface. Inspired by DeepSeek Harness, the plugin architecture provides the same configuration and management model on mobile and desktop.

<table>
  <tr>
    <td align="center" width="76%">
      <img src="docs/images/app-home-desktop.png" alt="kcode desktop home screen" width="760" />
      <br /><sub>Desktop</sub>
    </td>
    <td align="center" width="24%">
      <img src="docs/images/app-home.png" alt="kcode Android home screen" width="210" />
      <br /><sub>Android</sub>
    </td>
  </tr>
</table>

## Features

- **Conversation and tools**: streaming Markdown, visible tool progress, saved history, regeneration, and conversation image export; Web search, file editing, and shell execution.
- **Your choice of model**: built-in adapters for OpenAI, Azure OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter, Mistral, Qwen, Ollama, and GLM, plus Amazon Bedrock on desktop. Configure credentials and endpoints, or add providers through plugins.
- **Persistent goals**: create a task with `/goal <objective>` that continues across turns and restarts. Track status, elapsed time, and an optional token budget; pause or resume it when needed.
- **Multi-agent work**: delegate subtasks, exchange messages, and collect results. Inspect each worker's progress from the conversation.
- **Scheduled tasks**: run one-shot or recurring tasks with separate conversations and saved results. Tasks run while the application process is available; scheduling resumes after restart.
- **Skills**: add reusable knowledge, instructions, and workflows through `SKILL.md` packages.
- **Plugin management and recovery**: import new packages, configure plugins, and control enable states. Use an independent manager to switch configurations or repair startup problems.

## Get started

Download an Android APK or a desktop installer for your operating system from [GitHub Releases](https://github.com/meteor149/kcode/releases).

1. Open **Settings → Model provider** and enter your service credentials and connection options.
2. Return to the conversation and choose a model in the composer.
3. Ask for a task, such as researching a topic or editing workspace files, or create a persistent objective with `/goal`.

### Platform support

Android requires **Android 15 (API 35) or later**. Desktop supports Windows, macOS, and Linux.

| Capability | Android | Desktop |
| --- | --- | --- |
| Chat, files, search, Goals, Skills, scheduling, and multi-agent work | Supported | Supported |
| External plugin code | APK / dex | JAR |
| Shell execution | App, Shizuku (ADB), or root mode | Workspace shell |
| Bundled Ubuntu environment | ARM64, through PRoot | — |
| Background conversation overlay | Requires overlay permission | — |
| Amazon Bedrock | — | Supported |

Available capabilities depend on enabled plugins, the selected model, and system permissions. Shizuku and root modes require their respective authorization; PRoot does not grant additional Android privileges.

## Manage plugins

In **Settings → Plugins**, choose **Install plugin** to import a single `.kplugin` file. Review its metadata, configuration, and dependencies, then apply changes to activate it. Configuration and enable-state changes use the same workflow.

**Add instance** reuses an installed plugin, for example to configure two connections for one provider. Import a package file to install a new plugin.

### Independent manager

The host provides an independent manager that opens without loading the product plugin tree. It remains accessible when a broken plugin configuration prevents the main interface from starting.

- **Android**: open the **kcode Plugin Manager** launcher icon.
- **Desktop**: launch `kcode --plugin-manager`.

This entry also provides Profile creation, switching, import/export, history, and recovery. A Profile stores a plugin composition and its configuration. Edits are saved as drafts and become a new generation when activated. See the [Profile guide](docs/profiles.md), [plugin package format](docs/plugin-package-format.md), and [Bundle import guide](docs/profile-bundle-archives.md).

## Permissions and data

- Tool policies support denying, asking, or bypassing the approval prompt. Operating-system permissions still apply.
- Conversations and configuration are stored locally. Requests to remote models or search services are sent to the selected providers.
- Android credentials use encrypted MMKV with a Keystore-protected key; desktop settings live in the application data directory.

## Build from source

Use **JDK 21** and the checked-in Gradle wrapper. Android builds also require **SDK 35**. On Windows, replace `./gradlew` with `.\gradlew.bat`.

```bash
# Run desktop
./gradlew :apps:desktopApp:run

# Install the Android debug build
./gradlew :apps:androidApp:installDebug

# Run tests and build both hosts
./gradlew allTests :apps:desktopApp:compileKotlin :apps:androidApp:assembleDebug
```

Build desktop installers with `:apps:desktopApp:packageMsi`, `packageDmg`, or `packageDeb` on the corresponding operating system.

## Development and contributing

| Guide | Contents |
| --- | --- |
| [Documentation index](docs/README.md) | Topic guides |
| [Plugin architecture](docs/plugin-architecture.md) | SDK, service dependencies, and lifecycle |
| [Plugin development](docs/plugin-development.md) | Implementation, packaging, and integration |
| [Plugin package format](docs/plugin-package-format.md) | Metadata, platform variants, and API compatibility ranges |
| [Profiles](docs/profiles.md) | Configuration, activation, and recovery |
| [AGENTS.md](AGENTS.md) | Project structure, conventions, and contribution guidelines |

Issues, feature proposals, and pull requests are welcome. Include before/after media for interface changes and relevant tests for behavior changes.

## Acknowledgements

Thanks to the maintainers and contributors of the projects that power kcode:

| Project | How it helps kcode |
| --- | --- |
| [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Provide the multiplatform language, structured concurrency, and serialization foundation. |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform), [Material 3 / AndroidX](https://github.com/androidx/androidx), and [Haze](https://github.com/chrisbanes/haze) | Power the shared adaptive interface, design primitives, and visual effects. |
| [Koog](https://github.com/JetBrains/koog) and [Ktor](https://github.com/ktorio/ktor) | Form the agent, tool, model-provider, streaming, and networking foundation. |
| [Room](https://github.com/androidx/androidx/tree/androidx-main/room) and [SQLite](https://www.sqlite.org/) | Back local conversation persistence on Android and desktop. |
| [MMKV](https://github.com/Tencent/MMKV) and [Shizuku](https://github.com/RikkaApps/Shizuku) | Support mobile settings storage and explicit ADB-shell execution on Android. |
| [Operit](https://github.com/AAswordman/Operit), [OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore), [PRoot](https://github.com/proot-me/proot), [PRoot-Distro](https://github.com/termux/proot-distro), and [Ubuntu](https://ubuntu.com/) | Support and inspire the Android Ubuntu environment. See [NOTICE](NOTICE) for component attribution. |

Third-party licenses and attribution are recorded in [NOTICE](NOTICE) and the Gradle dependencies.

## License

Copyright 2026 The kcode Authors.

Licensed under [Apache License 2.0](LICENSE). Third-party components retain their own licenses; see [NOTICE](NOTICE) for attribution.
