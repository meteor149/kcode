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

> [!IMPORTANT]
> kcode 仍在快速演进中。版本间可能存在破坏性变更；请核对 Agent 执行的重要操作，并在启用高权限工具前阅读安全模型。

## 认识 kcode

kcode 是 Kotlin Multiplatform 生态中首个开源插件化 AI Harness，原生支持 Android 与桌面。它基于 [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)、[Koog](https://docs.koog.ai/) 与 Cordis 构建，将原生界面、可扩展的 Agent 运行时和本地数据存储融为一体。

**一切皆插件。** 按需组合模型、工具、提示词、权限策略与 UI，替换 Agent 循环、切换存储 Provider，或构建全新的根界面。kcode 借鉴 DeepSeek Harness 的插件模型，将同一套架构带到移动端与桌面端。

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

### 使用工具完成工作

- 流式输出 Markdown，展示工具进度并保存会话历史。
- 通过 Google、Exa 或 Bright Data 联网搜索，并返回来源链接。
- 读取、浏览、写入和局部修改工作区文件，在支持的平台读取媒体。
- 执行桌面 Shell 命令，或使用 Android 原生 Shell 与内置 Ubuntu 24.04 ARM64 环境。
- 停止或重新生成回复，将会话导出为长图。
- 在 Android 后台继续生成，并可通过会话浮窗查看进度。

### Goal 与多 Agent 协作

使用 `/goal <目标>` 创建持久任务。Goal 跨应用重启保存，记录状态、运行时间与可选 Token 预算，并跨回合持续推进。你可以在会话中暂停、恢复、编辑或取消目标。

明确要求派发子任务后，主 Agent 可以分配工作、交换消息并等待结果。默认组合支持五个 Agent 并发，包括主 Agent；点击会话中的子 Agent 即可查看活动与输出。

### 定时任务

直接要求创建单次或周期任务。每次运行使用独立会话，结果可加入历史或丢弃；平台支持时可发送后台通知。周期任务的最小间隔为一分钟。

任务依赖应用进程运行。重启后会恢复逾期任务，周期任务跳过错过的时间段，不会集中执行补偿任务。

### Skill 与 Web Artifact

在 `/workspace/.agents/skills` 或 `/workspace/.kcode/skills` 下添加 `SKILL.md` 包，为 Agent 提供可复用的指令与工作流。

内置 `kcode-web-app-builder` Skill 支持开发 Web 应用、在内嵌容器中测试、检查 DOM、收集控制台输出与截图并修复问题。经你确认后，应用会保存到 Artifact 应用库，供以后打开使用。Android 还可将定位、传感器、相机和文件选择等受支持的 Web API 桥接到原生能力，并遵循系统权限。

详见 [Artifact 指南](docs/artifacts.md)与 [Web 容器指南](plugins/web-container/WEB_CONTAINER_GUIDE.md)。

### 自由选择模型

内置适配器支持 OpenAI、Azure OpenAI、Anthropic、Google Gemini、DeepSeek、OpenRouter、Mistral AI、阿里云 DashScope / Qwen、Ollama、智谱 GLM，以及 Amazon Bedrock（桌面端）。

在设置中填写凭据、服务地址与供应商选项，在输入区选择模型与 Temperature。Ollama 支持无需 API Key 的本地服务；插件可扩展更多模型供应商。

## 平台支持

Android 与桌面共用对话、历史、Goal、调度、多 Agent、Skill、文件工具、联网搜索、Artifact 与会话长图导出。平台差异如下：

| 能力 | Android | 桌面 |
| --- | --- | --- |
| 外部插件 | APK/dex | JAR |
| 原生 Shell | 应用 UID / Shizuku / root | 工作区 Shell |
| 内置 Ubuntu 24.04 | ARM64，通过 PRoot 运行 | — |
| 原生 Web 能力桥 | 支持的设备 API | — |
| 后台会话浮窗 | 需要悬浮窗权限 | — |
| Amazon Bedrock 适配器 | — | 支持 |

实际能力取决于已安装插件、所选模型与平台权限。真实 Shizuku 授权和 root 执行仍需独立验证，详见[验证指南](docs/verification.md)。

## 快速开始

从 [GitHub Releases](https://github.com/meteor149/kcode/releases) 下载 Android APK、Windows MSI、macOS DMG 或 Linux DEB。

1. 打开**设置 → 大模型供应商**，选择服务并填写凭据与连接选项。
2. 返回会话，选择模型与 Temperature。
3. 要求 Agent 使用工具完成工作，通过 `/goal <目标>` 创建持久任务，或明确要求并行子任务与定时任务。

### 从源码运行

使用 JDK 21 与仓库自带的 Gradle Wrapper，JVM 字节码目标为 Java 17。Android 构建需要 SDK 35，运行需要 API 35+ 的设备或模拟器。Windows 下将 `./gradlew` 替换为 `.\gradlew.bat`。

```bash
# 运行桌面应用
./gradlew :apps:desktopApp:run

# 安装 Android Debug 构建
./gradlew :apps:androidApp:installDebug
```

在对应操作系统上，使用 `:apps:desktopApp` 的 `packageMsi`、`packageDmg` 或 `packageDeb` 任务构建桌面安装包。发布工作流会根据 Tag 构建 Android 与桌面产物。

### Android Shell 与 Ubuntu

`execute_shell_command` 执行 Android 原生 Shell；`execute_ubuntu_command` 在首次使用时安装内置 Ubuntu 根文件系统，并通过 PRoot 运行。Ubuntu 需要 ARM64 设备，运行时位置需至少保留 384 MiB 可用空间。

两个工具都遵循所选 Shell 模式：

- **应用**：使用应用 UID 与私有工作区。
- **ADB**：需要通过 adb 启动 Shizuku，以 UID 2000 运行，在 `/data/local/tmp/ai.meteor.kcode/ubuntu` 下使用独立运行时与工作区。
- **Root**：需要经过校验的 `su` 授权，与应用模式共用运行时与工作区。

ADB 工作区与应用文件工具的工作区分离。Root 创建的文件可能因属主或权限限制而无法在应用模式下访问。PRoot 提供 Linux 用户空间，不是虚拟机，也不额外授予 Android 权限。安装方式、产物来源与限制见 [Android Ubuntu 指南](docs/android-ubuntu-runtime.md)。

通过 adb 配置模型与搜索供应商时，请先启动 kcode，再按 [ADB 设置指南](docs/adb-settings.md)操作。保存成功返回 `result=-1`，不能只看 adb 退出码。

## 插件架构

原生 Host 提供平台基础能力，`plugins/runtime` 管理 Cordis 插件树，`plugins/bundle-native` 选择默认产品组合。只有清单与加载器是固定启动组件。功能插件声明服务依赖；必需 Provider 缺失时暂停 Consumer，恢复后重新绑定。

| 模块 | 职责 |
| --- | --- |
| `plugins/api` | 中立的领域、持久化、Agent、生命周期与开放 UI 贡献契约 |
| `plugins/default-ui-api` | 默认布局、页面、设置与展示组件的可选契约 |
| `libraries/ui` | 可复用 Compose 控件、设计契约与矢量图标 |
| `plugins/runtime` | 插件组合、生命周期所有权与已提交渲染快照 |
| `plugins/bundle-native` | 默认原生产品组合 |
| `plugins/installation-store` | 已安装包与启用状态持久化 |
| `apps/*`、`plugins/platform-*` | 应用 Host、Loader 与平台适配 |

产品实现在各功能模块中：`application`、`ui-pages` 与 `ui-settings` 提供默认界面，仓库、执行、模型、搜索与原生能力 Provider 各自拥有服务。Android 与桌面共用 Room Schema 和 Bundled SQLite。模块声明以 [settings.gradle.kts](settings.gradle.kts)为准。

通过插件管理器加载、启用、禁用、替换或卸载外部包，修改组合前先结束或取消活动 Agent 回合。安装状态跨重启保留，替换或清单发布失败时恢复已提交组合。撤销插件时先取消并等待其拥有的操作，再释放资源；切换存储 Provider 不会自动迁移数据。

外部包共享公开 SDK 类型，产品实现与私有依赖独立加载。当前 ABI 为 **Plugin API 34**，旧包需重新编译。自定义根渲染器可以自行选择服务，无需采用默认 UI 契约。

实现规则见[架构指南](docs/plugin-architecture.md)与[插件开发指南](docs/plugin-development.md)。[功能审计](docs/plugin-feature-audit.md)记录当前覆盖范围；[Harness 规范](docs/deepseek-harness-plugin-spec.md)与[预留 API 指南](docs/harness-reserved-api.md)区分已实现功能和尚无 Provider 的契约。全部指南收录在[文档索引](docs/README.md)。

## 权限与数据

- **工具策略**：`Deny`、`Ask`、`Bypass` 决定工具是否需要批准。`Bypass` 跳过 kcode 的确认，系统与 Web 权限仍然生效。定时任务使用执行时配置的模型，遵循与交互任务相同的权限策略。
- **文件**：桌面路径限制在托管 `/workspace` 内，拒绝路径穿越与符号链接逃逸。Android 还可接受真实绝对路径，但受文件系统权限限制。Skill 与 Artifact 资源受各自托管包边界约束。
- **凭据**：Android 使用加密 MMKV，密钥由 Keystore 保护；桌面设置保存在应用数据目录。ADB Receiver 要求系统 `DUMP` 权限，通过设置命令校验更新，命令执行期间参数对可信 Host 可见。
- **Android 执行**：Shizuku/root 不可用时直接失败，不静默切换身份；后台会话浮窗需要“显示在其他应用上层”权限。
- **Web 应用**：本地应用在运行时请求敏感能力，远程网站不获得本地原生能力桥。保存 Artifact 需要确认，并使用路径校验、容量限制与失败回滚。

安全问题请私下联系维护者。不要提交凭据、`local.properties`、设备截图或生成的数据库。

## 构建与测试

```bash
# 多平台测试与两个应用 Host
./gradlew allTests :apps:desktopApp:compileKotlin :apps:androidApp:assembleDebug

# SDK 与默认 UI 专项测试
./gradlew :plugins:api:allTests :plugins:default-ui-api:desktopTest

# 桌面组合与真实外部 JAR 加载
./gradlew :plugins:platform-desktop:test

# Android 外部 APK/dex 加载，需要 API 35+ 设备或模拟器
./gradlew :plugins:platform-android:connectedDebugAndroidTest
```

`allTests` 不包含连接设备的仪器测试。设备、Shizuku 与 root 检查的独立要求见[验证指南](docs/verification.md)。

## 致谢

感谢为 kcode 提供基础能力的开源项目及其维护者与贡献者：

| 项目 | 对 kcode 的帮助 |
| --- | --- |
| [Kotlin](https://github.com/JetBrains/kotlin)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) 与 [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 提供跨平台语言、结构化并发与序列化基础。 |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)、[Material 3 / AndroidX](https://github.com/androidx/androidx) 与 [Haze](https://github.com/chrisbanes/haze) | 支撑共享的自适应界面、设计基础与视觉效果。 |
| [Koog](https://github.com/JetBrains/koog) 与 [Ktor](https://github.com/ktorio/ktor) | 构成 Agent、工具、模型供应商、流式响应与网络访问的核心基础。 |
| [Room](https://github.com/androidx/androidx/tree/androidx-main/room) 与 [SQLite](https://www.sqlite.org/) | 支撑 Android 与桌面端的本地会话持久化。 |
| [MMKV](https://github.com/Tencent/MMKV) 与 [Shizuku](https://github.com/RikkaApps/Shizuku) | 分别支持移动端设置存储，以及 Android 上边界明确的 ADB shell 执行。 |
| [Operit](https://github.com/AAswordman/Operit)、[OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore)、[PRoot](https://github.com/proot-me/proot)、[PRoot-Distro](https://github.com/termux/proot-distro) 与 [Ubuntu](https://ubuntu.com/) | Operit 的运行时设计与 TerminalCore 的产物链路为 Android Ubuntu 实现提供了参考；随包 PRoot 二进制、Loader 和 Ubuntu 根文件系统的准确来源见[运行时说明](docs/android-ubuntu-runtime.md)与 [NOTICE](NOTICE)。 |

第三方协议与归属信息记录在 [NOTICE](NOTICE) 和 Gradle 依赖中。

## 参与贡献

欢迎提交 Issue 与 Pull Request。请先阅读 [AGENTS.md](AGENTS.md)，了解项目结构、代码规范、测试命令和 PR 要求。提交应保持职责单一、覆盖可观察行为；涉及 UI 时请附上前后对比截图或录屏。

## 开源协议

Copyright 2026 The kcode Authors.

本项目基于 [Apache License 2.0](LICENSE) 开源。第三方组件继续遵循各自协议，归属信息见 [NOTICE](NOTICE)。
