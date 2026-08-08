<div align="center">
  <img src="branding/kcode-mark-transparent.png" alt="kcode logo" width="112" />
  <h1>kcode</h1>
  <p><strong>A calm, local-first AI workspace for every screen.</strong></p>
  <p>One Compose Multiplatform UI. One Koog agent runtime. Your models, tools, and data.</p>

  <p>
    <a href="#english">English</a> ·
    <a href="#简体中文">简体中文</a>
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

---

<a id="english"></a>

## English

### What is kcode?

kcode is an open-source, cross-platform AI chat and agent application built with [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) and [Koog](https://docs.koog.ai/). It combines a focused chat experience with streaming Markdown, persistent history, visible tool execution, local workspaces, web search, and sandboxed H5 app previews—without maintaining a separate UI for every platform.

### Highlights

- **One UI everywhere** — shared adaptive Compose UI for Android, iOS, desktop, and Web.
- **Agent-native conversations** — real-time streaming, Markdown rendering, tool-call progress, stop/regenerate, message selection, and rendered long-image export on supported platforms.
- **Bring your own model** — OpenAI, Azure OpenAI, Anthropic, Google Gemini, DeepSeek, OpenRouter, Amazon Bedrock, Mistral AI, Alibaba DashScope, Ollama, and Zhipu GLM.
- **Useful built-in tools** — bounded `/workspace` file operations, Google/Exa/Bright Data web search, and local H5 preview.
- **Mobile capability bridge** — local H5 apps can request camera, location, compass, motion sensors, vibration, battery, network, and other platform-reported capabilities.
- **Explicit permission gate** — globally choose `Deny`, `Ask`, or `Bypass` for tool calls. Operating-system permissions still apply.
- **Local-first persistence** — versioned settings and Room/SQLite conversation history with platform-native storage boundaries.

### Platform support

| Capability | Android | iOS | Desktop | Web |
| --- | :---: | :---: | :---: | :---: |
| Shared Compose UI and Koog agent | ✅ | ✅ | ✅ | ✅ |
| Streaming Markdown and persistent history | ✅ | ✅ | ✅ | ✅ |
| Sandboxed file workspace and web search | ✅ | ✅ | ✅ | ✅ |
| Local H5 app preview | ✅ | ✅ | ✅ | ✅ |
| Rendered conversation image export | ✅ | — | ✅ | — |
| Mobile hardware bridge | ✅ | ✅ | — | Browser APIs |
| Shell tool | App UID / Shizuku / root | — | — | — |
| Amazon Bedrock client | — | — | ✅ | — |

Availability also depends on the device, browser, model provider, and granted permissions. Browser-side provider calls are subject to CORS.

### Quick start

#### Requirements

- JDK 21 (the JVM bytecode target remains Java 17)
- Android Studio and Android SDK 35 for Android
- macOS, Xcode, and [XcodeGen](https://github.com/yonaskolb/XcodeGen) for iOS
- A modern browser for the Wasm target

Use the checked-in Gradle wrapper from the repository root. On Windows, replace `./gradlew` with `gradlew.bat`.

#### Desktop

```bash
./gradlew :composeApp:run
```

#### Android

Start an API 35+ emulator or connect a device, then run:

```bash
./gradlew :androidApp:installDebug
```

#### Web

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

#### iOS

```bash
cd iosApp
xcodegen generate
open iosApp.xcodeproj
```

The Xcode target builds and embeds the shared `KcodeShared` framework. The deployment target is iOS 14.

### Configure a provider

Open **Settings → Model providers**, select a provider, and enter its credentials. Choose the active model and generation options from the conversation composer. Ollama can use a local endpoint without an API key.

Credential handling differs by platform:

- Android encrypts API keys with Android Keystore.
- iOS stores secrets in Keychain.
- Desktop currently stores configuration under `~/.kcode`; desktop secret-store integration is still planned.
- Web stores configuration in `localStorage`; use only on a trusted origin and prefer a server-side gateway for production deployments.

### Security model

- Agent file tools are confined to a virtual `/workspace`; traversal and symbolic-link escapes are rejected and size limits are enforced.
- Tool calls pass through the global permission gate. `Bypass` skips the kcode confirmation only—it does not bypass Android/iOS/browser permissions.
- Android shell execution has explicit identity modes and bounded time/output. Shizuku and root are opt-in external privilege sources.
- H5 apps run in an isolated container, query capability availability at runtime, and request approval for sensitive access.
- Never commit API keys, `local.properties`, device captures, or generated databases.

Please report security-sensitive issues privately to the maintainers instead of publishing exploit details in a public issue.

### Architecture

```text
androidApp/         Android application host
composeApp/         Shared UI, agent runtime, settings, and platform bridges
  commonMain/       UI, state, localization, models, and service contracts
  agentMain/        Koog runtime and cross-platform agent tools
  *Main/            Android, iOS, desktop, and Web implementations
historyStore/       Room 3 schema and cross-platform SQLite persistence
h5Container/        Isolated local H5 runtimes and capability bridges
webSearch/          Google, Exa, and Bright Data search tool
sqliteWasmWorker/   SQLite Wasm worker and OPFS bridge
iosApp/             Lightweight SwiftUI host
docs/               Design and engineering documentation
```

Android/iOS/desktop use the same Room schema with bundled SQLite. Web keeps the same schema through a worker-backed SQLite database in OPFS.

### Build and test

```bash
# Main multiplatform tests
./gradlew :composeApp:allTests

# All available module tests
./gradlew allTests

# Android debug APK
./gradlew :androidApp:assembleDebug

# Production-style Web bundle
./gradlew :composeApp:wasmJsBrowserProductionWebpack
```

### Contributing

Contributions are welcome. Read [AGENTS.md](AGENTS.md) for repository structure, conventions, test commands, and pull-request expectations. Keep changes focused, add tests for observable behavior, and include screenshots or recordings for UI changes.

---

<a id="简体中文"></a>

## 简体中文

### kcode 是什么？

kcode 是一款基于 [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) 与 [Koog](https://docs.koog.ai/) 构建的开源跨平台 AI 对话与 Agent 应用。它以同一套 UI 覆盖 Android、iOS、桌面与 Web，并将流式 Markdown、对话持久化、可见的工具调用、本地工作区、联网搜索和沙箱 H5 应用预览整合在一个专注、克制的交互体验中。

### 核心特性

- **一套 UI，覆盖全端**——Android、iOS、桌面和 Web 共用自适应 Compose UI。
- **面向 Agent 的对话体验**——实时流式输出、Markdown 渲染、工具调用过程、停止与重新生成、消息多选，以及在已支持平台导出带渲染效果的长图。
- **自带模型，自由切换**——支持 OpenAI、Azure OpenAI、Anthropic、Google Gemini、DeepSeek、OpenRouter、Amazon Bedrock、Mistral AI、阿里云 DashScope、Ollama 和智谱 GLM。
- **实用的内置工具**——受限 `/workspace` 文件读写、Google/Exa/Bright Data 联网搜索和本地 H5 预览。
- **移动硬件能力桥**——本地 H5 应用可按平台申请摄像头、定位、指南针、运动传感器、振动、电池和网络等能力。
- **统一权限审批门**——所有工具调用可统一设置为 `Deny`、`Ask` 或 `Bypass`；系统权限仍然有效。
- **本地优先持久化**——设置采用版本化存储，对话历史通过 Room/SQLite 跨端保存。

### 平台支持

| 能力 | Android | iOS | 桌面 | Web |
| --- | :---: | :---: | :---: | :---: |
| 共享 Compose UI 与 Koog Agent | ✅ | ✅ | ✅ | ✅ |
| 流式 Markdown 与历史持久化 | ✅ | ✅ | ✅ | ✅ |
| 沙箱文件工作区与联网搜索 | ✅ | ✅ | ✅ | ✅ |
| 本地 H5 应用预览 | ✅ | ✅ | ✅ | ✅ |
| 渲染后会话长图导出 | ✅ | — | ✅ | — |
| 移动硬件能力桥 | ✅ | ✅ | — | 浏览器 API |
| Shell 工具 | 应用 UID / Shizuku / root | — | — | — |
| Amazon Bedrock 客户端 | — | — | ✅ | — |

实际可用性还取决于设备、浏览器、模型供应商及用户授予的权限；浏览器直连模型服务也会受到 CORS 策略限制。

### 快速开始

#### 环境要求

- JDK 21（应用 JVM 字节码目标仍为 Java 17）
- Android 端需要 Android Studio 与 Android SDK 35
- iOS 端需要 macOS、Xcode 与 [XcodeGen](https://github.com/yonaskolb/XcodeGen)
- Web 端需要现代浏览器

请从项目根目录使用仓库自带的 Gradle Wrapper。Windows 用户可将 `./gradlew` 替换为 `gradlew.bat`。

#### 桌面端

```bash
./gradlew :composeApp:run
```

#### Android

启动 API 35 或更高版本的模拟器，或连接 Android 设备，然后执行：

```bash
./gradlew :androidApp:installDebug
```

#### Web

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

#### iOS

```bash
cd iosApp
xcodegen generate
open iosApp.xcodeproj
```

Xcode Target 会自动构建并嵌入共享的 `KcodeShared` Framework，最低部署版本为 iOS 14。

### 配置模型供应商

进入**设置 → 大模型供应商**，选择供应商并填写凭据；当前模型和生成参数可直接从会话输入区调整。Ollama 支持无需 API Key 的本地服务地址。

各平台的凭据存储方式不同：

- Android 使用 Android Keystore 加密 API Key。
- iOS 使用 Keychain 保存敏感信息。
- 桌面端目前将配置保存在 `~/.kcode`，后续仍需接入各系统原生密钥存储。
- Web 使用 `localStorage`，只应在可信站点使用；生产部署推荐通过自己的服务端网关访问模型。

### 安全边界

- Agent 文件工具只能访问虚拟 `/workspace`，会拒绝路径穿越和符号链接逃逸，并限制文件与工作区大小。
- 所有工具调用都会经过统一权限审批门。`Bypass` 仅跳过 kcode 的确认，不会绕过 Android、iOS 或浏览器系统权限。
- Android Shell 明确区分应用 UID、Shizuku 与 root 身份，并限制执行时间和输出大小；高权限来源必须由用户主动配置。
- H5 应用运行在隔离容器中，通过统一 API 查询能力是否可用，并在访问敏感能力前请求授权。
- 请勿提交 API Key、`local.properties`、设备截图、调试日志或生成的数据库。

发现安全问题时，请优先私下联系维护者，不要在公开 Issue 中披露可利用细节。

### 工程结构

```text
androidApp/         Android 应用入口
composeApp/         共享 UI、Agent 运行时、设置与平台桥接
  commonMain/       UI、状态、本地化、模型与服务协议
  agentMain/        Koog 运行时与跨平台 Agent 工具
  *Main/            Android、iOS、桌面和 Web 平台实现
historyStore/       Room 3 Schema 与跨平台 SQLite 持久化
h5Container/        隔离 H5 运行时与硬件能力桥
webSearch/          Google、Exa 与 Bright Data 搜索工具
sqliteWasmWorker/   SQLite Wasm Worker 与 OPFS 桥接
iosApp/             轻量 SwiftUI Host
docs/               设计与工程文档
```

Android、iOS 与桌面端使用相同的 Room Schema 和 Bundled SQLite；Web 通过 Worker 将同一 Schema 的 SQLite 数据库保存到 OPFS。

### 构建与测试

```bash
# 主模块跨平台测试
./gradlew :composeApp:allTests

# 所有可用模块测试
./gradlew allTests

# 构建 Android Debug APK
./gradlew :androidApp:assembleDebug

# 构建 Web 生产包
./gradlew :composeApp:wasmJsBrowserProductionWebpack
```

### 参与贡献

欢迎提交 Issue 和 Pull Request。请先阅读 [AGENTS.md](AGENTS.md)，了解项目结构、代码规范、测试命令和 PR 要求。提交应保持职责单一，为可观察行为补充测试；涉及 UI 时请附上截图或录屏。

---

## License / 开源协议

Copyright 2026 The kcode Authors.

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify, and distribute this project, including for commercial purposes, subject to the license terms. The license includes an express patent grant and requires preservation of applicable copyright, license, and NOTICE information. Third-party components remain under their respective licenses. Use of the kcode name and logo is governed by the trademark provisions in Section 6 of Apache-2.0.

本项目基于 [Apache License 2.0](LICENSE) 开源。在遵守协议条款的前提下，你可以使用、修改、分发本项目，也可以将其用于商业用途。该协议包含明确的专利授权，并要求保留适用的版权、协议与 NOTICE 信息。第三方组件继续遵循各自的开源协议；kcode 名称与 Logo 的使用受 Apache-2.0 第 6 条商标条款约束。

See [NOTICE](NOTICE) for attribution information.
