<div align="center">
  <img src="branding/kcode-mark-transparent.png" alt="kcode 标志" width="112" />
  <h1>kcode</h1>
  <p><strong>基于 Kotlin 构建、以插件为核心的原生 AI Harness。</strong></p>
  <p>一切皆插件。原生支持 Android 与桌面。模型、工具、界面与数据，由你掌控。</p>

  <p>
    <a href="README.md">English</a> · <strong>简体中文</strong>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.3.21" />
    <img src="https://img.shields.io/badge/Compose_Multiplatform-1.8.2-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose Multiplatform 1.8.2" />
    <img src="https://img.shields.io/badge/Koog-1.1.1-8FD694" alt="Koog 1.1.1" />
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-3DA639" alt="Apache License 2.0" /></a>
  </p>
</div>

## 认识 kcode

kcode 是原生支持 Android 与桌面的 AI 助手，基于 Kotlin Multiplatform、Compose Multiplatform、Koog 与 [Cordis](https://github.com/meteor149/cordis-kotlin) 构建。它将对话、工具调用和本地工作区结合起来，让 Agent 能够从回答问题进一步完成实际任务。

**一切皆插件。** 模型、工具、Agent 运行流程、存储和界面都可以按需组合与替换。插件架构借鉴 DeepSeek Harness，在移动端与桌面端使用相同的配置与管理方式。

<table>
  <tr>
    <td align="center" width="76%">
      <img src="docs/images/app-home-desktop.png" alt="kcode 桌面主页" width="760" />
      <br /><sub>桌面</sub>
    </td>
    <td align="center" width="24%">
      <img src="docs/images/app-home.png" alt="kcode Android 主页" width="210" />
      <br /><sub>Android</sub>
    </td>
  </tr>
</table>

## 核心能力

- **对话与工具调用**：流式 Markdown、工具执行进度、会话历史、重新生成和长图导出；支持联网搜索、文件读写与 Shell 执行。
- **模型自由选择**：内置 OpenAI、Azure OpenAI、Anthropic、Gemini、DeepSeek、OpenRouter、Mistral、Qwen、Ollama 与 GLM 等适配器；桌面端还支持 Amazon Bedrock。可配置凭据和服务地址，插件也能扩展模型来源。
- **持续目标**：使用 `/goal <目标>` 创建跨轮次、跨重启的任务，查看状态、用时和可选的 Token 预算，随时暂停或继续。
- **多 Agent 协作**：让 Agent 分派子任务、交换消息和汇总结果，在会话中查看各个工作 Agent 的进度。
- **定时任务**：创建一次性或周期任务，独立保存每次运行的会话与结果。任务在应用进程可用时运行，重启后恢复调度。
- **Skills**：通过 `SKILL.md` 包添加可复用的知识、指令和工作流程。
- **插件管理与恢复**：导入新的插件包，调整配置与启用状态；独立管理入口用于切换配置方案和修复启动问题。

## 快速开始

从 [GitHub Releases](https://github.com/meteor149/kcode/releases) 下载 Android APK 或对应系统的桌面安装包。

1. 打开 **设置 → 模型提供商**，填写服务凭据与连接选项。
2. 返回会话，在输入区选择模型。
3. 提出任务，例如搜索资料、修改工作区文件，或用 `/goal` 创建持续目标。

### 平台能力

Android 需要 **Android 15（API 35）或更新版本**。桌面支持 Windows、macOS 和 Linux。

| 能力 | Android | 桌面 |
| --- | --- | --- |
| 对话、文件、搜索、Goal、Skills、定时任务与多 Agent | 支持 | 支持 |
| 外部插件代码 | APK / dex | JAR |
| Shell 执行 | App、Shizuku（ADB）或 root 模式 | 工作区 Shell |
| 内置 Ubuntu 环境 | ARM64，通过 PRoot 运行 | — |
| 后台会话浮窗 | 需授予悬浮窗权限 | — |
| Amazon Bedrock | — | 支持 |

具体能力取决于已启用的插件、所选模型和系统授权。Shizuku 与 root 模式需要相应授权，PRoot 不会额外提升 Android 权限。

## 管理插件

在 **设置 → 插件管理** 中选择“安装插件”，导入单个 `.kplugin` 文件。检查插件信息、配置与依赖后，点击“应用修改”激活；已有插件的配置和启用状态也通过同一流程更新。

“添加实例”用于复用已经安装的插件，例如为同一提供商配置不同连接；安装新的插件使用文件导入。

### 独立管理入口

独立入口由宿主提供，打开时不加载产品插件树，因此插件配置错误导致主界面无法启动时，仍可进入管理界面修复。

- **Android**：打开启动器中的 **kcode 插件管理** 图标。
- **桌面**：使用 `kcode --plugin-manager` 启动。

独立入口还提供 Profile 的创建、切换、导入导出、历史版本和恢复能力。Profile 保存一套插件组合与配置，修改先保存为草稿，激活后成为新的版本。详见 [Profile 指南](docs/profiles.md)、[插件包格式](docs/plugin-package-format.md)和 [Bundle 导入](docs/profile-bundle-archives.md)。

## 权限与数据

- 工具权限支持拒绝、询问与直接执行；系统权限仍按平台规则生效。
- 会话与配置保存在本地。使用远程模型或搜索服务时，相关请求会发送给所选服务商。
- Android 凭据使用加密 MMKV 与 Keystore 存储；桌面配置保存在应用数据目录。

## 从源码构建

使用 **JDK 21** 和仓库自带的 Gradle Wrapper。Android 构建还需要 **SDK 35**。Windows 下将 `./gradlew` 替换为 `.\gradlew.bat`。

```bash
# 运行桌面应用
./gradlew :apps:desktopApp:run

# 安装 Android 调试版本
./gradlew :apps:androidApp:installDebug

# 运行测试并构建两个宿主
./gradlew allTests :apps:desktopApp:compileKotlin :apps:androidApp:assembleDebug
```

桌面安装包通过 `:apps:desktopApp:packageMsi`、`packageDmg` 或 `packageDeb` 在对应系统上构建。

## 开发与贡献

| 入口 | 内容 |
| --- | --- |
| [文档索引](docs/README.md) | 各专题指南 |
| [插件架构](docs/plugin-architecture.md) | SDK、服务依赖与生命周期 |
| [插件开发](docs/plugin-development.md) | 插件实现、打包与接入 |
| [插件包格式](docs/plugin-package-format.md) | 元数据、平台变体与 API 兼容范围 |
| [Profile 指南](docs/profiles.md) | 配置方案、激活与恢复 |
| [AGENTS.md](AGENTS.md) | 项目结构、代码规范与贡献约定 |

欢迎提交问题、功能建议和 Pull Request。界面改动请附前后对比，行为改动请提供对应测试。

## 致谢

感谢为 kcode 提供基础能力的开源项目及其维护者与贡献者：

| 项目 | 对 kcode 的帮助 |
| --- | --- |
| [Kotlin](https://github.com/JetBrains/kotlin)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) 与 [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 提供跨平台语言、结构化并发与序列化基础。 |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)、[Material 3 / AndroidX](https://github.com/androidx/androidx) 与 [Haze](https://github.com/chrisbanes/haze) | 支撑共享的自适应界面、设计基础与视觉效果。 |
| [Koog](https://github.com/JetBrains/koog) 与 [Ktor](https://github.com/ktorio/ktor) | 构成 Agent、工具、模型供应商、流式响应与网络访问的核心基础。 |
| [Room](https://github.com/androidx/androidx/tree/androidx-main/room) 与 [SQLite](https://www.sqlite.org/) | 支撑 Android 与桌面端的本地会话持久化。 |
| [MMKV](https://github.com/Tencent/MMKV) 与 [Shizuku](https://github.com/RikkaApps/Shizuku) | 分别支持移动端设置存储，以及 Android 上边界明确的 ADB shell 执行。 |
| [Operit](https://github.com/AAswordman/Operit)、[OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore)、[PRoot](https://github.com/proot-me/proot)、[PRoot-Distro](https://github.com/termux/proot-distro) 与 [Ubuntu](https://ubuntu.com/) | 为 Android Ubuntu 环境提供实现基础与设计参考，组件归属见 [NOTICE](NOTICE)。 |

第三方协议与归属信息记录在 [NOTICE](NOTICE) 和 Gradle 依赖中。

## 开源协议

Copyright 2026 The kcode Authors.

项目采用 [Apache License 2.0](LICENSE)。第三方组件遵循各自的许可证，相关声明见 [NOTICE](NOTICE)。
