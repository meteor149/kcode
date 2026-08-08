<div align="center">
  <img src="../branding/kcode-mark-transparent.png" alt="kcode 标志" width="112" />
  <h1>kcode</h1>
  <p><strong>为每一块屏幕而生的安静、本地优先 AI 工作空间。</strong></p>
  <p>一套 Compose Multiplatform UI，一个 Koog Agent 运行时，以及属于你的模型、工具与数据。</p>

  <p>
    <a href="../README.md">English</a> · <strong>简体中文</strong>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.3.21" />
    <img src="https://img.shields.io/badge/Compose_Multiplatform-1.8.2-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose Multiplatform 1.8.2" />
    <img src="https://img.shields.io/badge/Koog-1.0.0-8FD694" alt="Koog 1.0.0" />
    <a href="../LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-3DA639" alt="Apache License 2.0" /></a>
  </p>
</div>

> [!IMPORTANT]
> kcode 正处于积极开发阶段。当前请从源码构建，版本间可能存在破坏性变更；启用高权限工具前，请先阅读安全边界说明。

## kcode 是什么？

kcode 是一款基于 [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) 与 [Koog](https://docs.koog.ai/) 构建的开源跨平台 AI 对话与 Agent 应用。它以同一套 UI 覆盖 Android、iOS、桌面与 Web，并将流式 Markdown、对话持久化、可见的工具调用、本地工作区、联网搜索和沙箱 H5 应用预览整合在一个专注、克制的交互体验中。

## 核心特性

- **一套 UI，覆盖全端**——Android、iOS、桌面和 Web 共用自适应 Compose UI。
- **面向 Agent 的对话体验**——实时流式输出、Markdown 渲染、工具调用过程、停止与重新生成、消息多选，以及在已支持平台导出带渲染效果的长图。
- **自带模型，自由切换**——支持 OpenAI、Azure OpenAI、Anthropic、Google Gemini、DeepSeek、OpenRouter、Amazon Bedrock、Mistral AI、阿里云 DashScope、Ollama 和智谱 GLM。
- **实用的内置工具**——受限 `/workspace` 文件读写、Google/Exa/Bright Data 联网搜索和本地 H5 预览。
- **移动硬件能力桥**——本地 H5 应用可按平台申请摄像头、定位、指南针、运动传感器、振动、电池和网络等能力。
- **统一权限审批门**——所有工具调用可统一设置为 `Deny`、`Ask` 或 `Bypass`；系统权限仍然有效。
- **本地优先持久化**——设置采用版本化存储，对话历史通过 Room/SQLite 跨端保存。

## 平台支持

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

## 快速开始

### 环境要求

- JDK 21（应用 JVM 字节码目标仍为 Java 17）
- Android 端需要 Android Studio 与 Android SDK 35
- iOS 端需要 macOS、Xcode 与 [XcodeGen](https://github.com/yonaskolb/XcodeGen)
- Web 端需要现代浏览器

请从项目根目录使用仓库自带的 Gradle Wrapper。Windows 用户可将 `./gradlew` 替换为 `gradlew.bat`。

### 桌面端

```bash
./gradlew :shared:run
```

### Android

启动 API 35 或更高版本的模拟器，或连接 Android 设备，然后执行：

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

Xcode Target 会自动构建并嵌入共享的 `KcodeShared` Framework，最低部署版本为 iOS 14。

## 配置模型供应商

进入**设置 → 大模型供应商**，选择供应商并填写凭据；当前模型和生成参数可直接从会话输入区调整。Ollama 支持无需 API Key 的本地服务地址。

各平台的凭据存储方式不同：

- Android 使用 Android Keystore 加密 API Key。
- iOS 使用 Keychain 保存敏感信息。
- 桌面端目前将配置保存在 `~/.kcode`，后续仍需接入各系统原生密钥存储。
- Web 使用 `localStorage`，只应在可信站点使用；生产部署推荐通过自己的服务端网关访问模型。

## 安全边界

- Agent 文件工具只能访问虚拟 `/workspace`，会拒绝路径穿越和符号链接逃逸，并限制文件与工作区大小。
- 所有工具调用都会经过统一权限审批门。`Bypass` 仅跳过 kcode 的确认，不会绕过 Android、iOS 或浏览器系统权限。
- Android Shell 明确区分应用 UID、Shizuku 与 root 身份，并限制执行时间和输出大小；高权限来源必须由用户主动配置。
- H5 应用运行在隔离容器中，通过统一 API 查询能力是否可用，并在访问敏感能力前请求授权。
- 请勿提交 API Key、`local.properties`、设备截图、调试日志或生成的数据库。

发现安全问题时，请优先私下联系维护者，不要在公开 Issue 中披露可利用细节。

## 工程结构

```text
apps/
  androidApp/       Android 应用入口
  iosApp/           轻量 SwiftUI Host
  web/
    sqliteWasmWorker/ SQLite Wasm Worker 模块与 OPFS 桥接
shared/             统一的 Compose Multiplatform 共享模块
  src/commonMain/   UI、状态、Room Schema、搜索与服务协议
  src/agentMain/    Koog 运行时与跨平台 Agent 工具
  src/*Main/        Android、iOS、桌面和 Web 平台实现
  schemas/          Room 迁移 Schema
extensions/
  h5Container/      隔离 H5 运行时与硬件能力桥
docs/               设计与工程文档
```

Android、iOS 与桌面端使用相同的 Room Schema 和 Bundled SQLite；Web 通过 Worker 将同一 Schema 的 SQLite 数据库保存到 OPFS。

## 构建与测试

```bash
# 主模块跨平台测试
./gradlew :shared:allTests

# 所有可用模块测试
./gradlew allTests

# 构建 Android Debug APK
./gradlew :apps:androidApp:assembleDebug

# 构建 Web 生产包
./gradlew :shared:wasmJsBrowserProductionWebpack
```

## 参与贡献

欢迎提交 Issue 和 Pull Request。请先阅读 [AGENTS.md](../AGENTS.md)，了解项目结构、代码规范、测试命令和 PR 要求。提交应保持职责单一，为可观察行为补充测试；涉及 UI 时请附上截图或录屏。

## 开源协议

Copyright 2026 The kcode Authors.

本项目基于 [Apache License 2.0](../LICENSE) 开源。在遵守协议条款的前提下，你可以使用、修改、分发本项目，也可以将其用于商业用途。该协议包含明确的专利授权，并要求保留适用的版权、协议与 NOTICE 信息。第三方组件继续遵循各自的开源协议；kcode 名称与 Logo 的使用受 Apache-2.0 第 6 条商标条款约束。

版权归属信息请参阅 [NOTICE](../NOTICE)。
