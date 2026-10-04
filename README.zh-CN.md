<div align="center">
  <img src="branding/kcode-mark-transparent.png" alt="kcode 标志" width="112" />
  <h1>kcode</h1>
  <p><strong>优雅、全功能、跨平台的原生 AI Agent。</strong></p>
  <p>一套自适应 Compose UI，一个完整的 Agent 运行时，以及属于你的模型、工具、Skill 与数据。</p>

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

## 我们想做什么？

kcode 是一款面向 Android 和桌面的开源原生 AI Agent。我们相信，Agent 应该是一款经过认真设计的应用：它不应只是塞进聊天框的终端，也不应是为每个平台重复包装的一层网页。

项目将一套自适应 [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) 界面与基于 [Koog](https://docs.koog.ai/) 的 Agent 运行时结合起来，并在此基础上提供本地优先存储、真实工具、可复用 Skill、持久化 Goal、定时自动化、多 Agent 编排和可运行的 Web Artifact。一次对话可以自然地从普通问答进入工具执行，再延伸为持续推进的长期目标、周期任务，或沉淀为可以直接打开使用的小应用。

kcode 的架构目标是实现 Kotlin 版本的 DeepSeek Harness 插件模型：“一切皆插件”。Android 和桌面端共用一棵 [Cordis 插件树](docs/plugin-architecture.md)，支持替换 Agent 与应用服务。现有应用功能已按可替换插件组装，[功能审计](docs/plugin-feature-audit.md)记录实现归属与生命周期证据。[Harness 插件规范](docs/deepseek-harness-plugin-spec.md)还包括仅预留 API、尚未提供实现的未来子系统。真实 Shizuku 授权及 root 执行尚未验证。

## kcode 能做什么？

### 不止对话，真正完成任务

- 实时输出富 Markdown，并将助手回复与工具调用记录保留在会话中。
- 原位展示工具执行进度与结果，支持停止、重新生成、消息选择，以及在已支持平台导出渲染后的会话长图。
- 在已支持的移动端 Host 进入后台后继续保持生成任务运行；Android 离开前台后还可通过可拖动的系统浮窗展示实时对话与工具活动。
- 通过 Google、Exa 或 Bright Data 搜索最新公开网页，并返回来源链接。
- 读取、浏览、写入和局部修改工作区文件；平台实现允许时还可读取媒体文件。
- 在桌面端工作区执行 Shell。Android 同时提供原生 `/system/bin/sh` 与完整 Ubuntu 24.04 ARM64 用户空间，两者都会采用用户所选的应用 UID、Shizuku/ADB shell 或真实 root 身份。
- 具备外部操作能力的工具统一经过 `Deny`、`Ask` 或 `Bypass` 权限策略；内部多 Agent 协作与 Goal 状态维护会自动执行。

### 规划并推进长期任务

**Goal** 可以把一次会话变成持久化目标。Goal 会跨应用重启保存，记录状态、运行时间与可选 Token 预算，并允许 Agent 跨多个回合持续推进，直到真正完成或确实受阻。使用 `/goal <目标>` 创建 Goal，之后可在会话中暂停、恢复、编辑或取消；活动 Goal 会显示在输入框上方，已完成 Goal 会自动退出界面。

**多 Agent 编排**允许根 Agent 把边界清晰的子任务并行派发给工作 Agent，在 Agent 间传递消息、中断或复用已有 Agent，并等待所有结果后再统一汇总。kcode 最多支持 5 个并发 Agent（包含根 Agent）。运行中的子 Agent 会以双列状态栏直接显示在输入框上方，点击即可查看其活动与输出内容。

### 安排单次与周期任务

当用户明确提出要求时，Agent 可以为当前会话创建、查看、暂停、恢复和取消定时 Prompt。任务既可以在延迟一段时间后或指定时间运行一次，也可以按不短于一分钟的间隔重复运行。每次执行都会创建独立会话，在浮层卡片中显示由 Agent 选定的结果，并允许加入普通历史记录或直接丢弃。Android 和桌面共用同一套持久化任务模型；如果平台支持且用户已授权，任务在后台完成时会发送对应平台的通知。

当前调度器依赖 kcode 应用进程，并非操作系统级闹钟服务。应用重新启动后会恢复已持久化的逾期任务；周期任务会跳过已经错过的时间槽，不会同时启动一批补偿执行。

### 使用 Skill 扩展能力

kcode 会从 `/workspace/.agents/skills` 与 `/workspace/.kcode/skills` 发现 `SKILL.md` 包，并只向当前任务注入相关说明。Skill 可以承载领域知识、可重复工作流与工具使用规范，不需要把这些内容长期堆叠在系统提示词中。运行时会校验 Skill 的包边界，并通过基于 Authority 的 Provider 模型为更多 Skill 来源保留扩展能力。

内置的 `kcode-web-app-builder` Skill 覆盖完整的 Web 应用工作流：实现响应式应用、在真实 Web 容器中打开、检查并操作界面、收集控制台输出与截图、修复缺陷，并且只有得到用户明确同意后，才会将应用保存为 Artifact。

### 构建并保留可运行的 Artifact

Web Artifact 是由 Agent 工作区托管的本地小应用。Agent 可以直接从对话开始开发，在产品实际使用的 Web 容器中调试，然后将成品保存到 Artifact 应用库。保存后的应用会像原生应用入口一样启动，而不是被埋没在历史消息里。

Web 容器支持本地应用与远程网站、前后台生命周期、活动容器悬浮坞、DOM 检查、安全交互句柄、控制台收集、截图和响应式调试。Android 还会在不绕过系统权限的前提下，将定位、运动传感器、振动、电池、相机、麦克风和文件选择等可用 Web API 桥接到原生设备能力。具体实现约束请参阅 [Artifact 存储](docs/artifacts.md)与 [Web 容器说明](plugins/web-container/WEB_CONTAINER_GUIDE.md)。

### 自由选择模型

kcode 当前已集成：

- OpenAI 与 Azure OpenAI
- Anthropic
- Google Gemini
- DeepSeek
- OpenRouter
- Amazon Bedrock（桌面端）
- Mistral AI
- 阿里云 DashScope / Qwen
- Ollama
- 智谱 GLM

模型供应商、模型、服务地址、区域、凭据与 Temperature 均可在应用内配置。Ollama 可以连接无需 API Key 的本地服务。

## 插件架构与当前边界

只有插件清单与加载器是固定的启动组件。默认组合允许禁用或替换产品插件，包括模型适配、提示词、权限、工具、Koog 循环、设置、会话历史、Artifact 存储、Web 容器与应用渲染器。Consumer 通过 Cordis 获取服务，并在 Provider 变化后重新绑定。

桌面端支持外部 JAR 插件，Android 支持 APK/dex 插件。运行时支持加载、启用、禁用、替换与卸载，替换失败时支持回滚。真实外部插件包加载已由桌面测试与 Android 设备仪器测试覆盖。修改插件组合前，需要先结束或取消正在执行的 Agent 回合。

当前应用组合内置插件，并从应用私有目录的原子清单恢复外部插件包和启用状态。当前插件 API 为 34，旧外部包需重新编译。清单发布失败会撤销运行时变更，不兼容的声明 API 版本在加载前拒绝。应用渲染器、布局、侧栏、页面、主题、设置分区、消息、工具卡片和应用副作用均有独立插件贡献。会话执行、历史会话、Goal、调度、导出和各模型供应商由可管理的服务或 Consumer 提供；依赖缺失时暂停 Consumer，撤销时取消并等待拥有的操作。尚未实现的 Harness jobs、subprocess/PTY、完整会话事件日志、文件观察与版本守卫已提供[预留契约](docs/harness-reserved-api.md)，不挂载伪实现。[验证指南](docs/verification.md)记录验收标准和证据范围；真实 Shizuku/root 权限环境仍需独立验证。实现细节与边界见[插件架构说明](docs/plugin-architecture.md)。

## 平台支持

| 能力 | Android | 桌面 |
| --- | :---: | :---: |
| 自适应原生 Compose UI | ✅ | ✅ |
| 模型对话与本地历史记录 | ✅ | ✅ |
| 流式 Koog Agent 运行时 | ✅ | ✅ |
| 持久化 Goal | ✅ | ✅ |
| 单次与周期定时任务 | ✅ | ✅ |
| 多 Agent 编排与 Skill | ✅ | ✅ |
| Agent 文件工作区与联网搜索 | ✅ | ✅ |
| Web Artifact 与容器 | ✅ | ✅ |
| 会话长图导出 | ✅ | ✅ |
| 移动端原生 Web 能力桥 | ✅ | — |
| 原生系统 Shell 工具 | 应用 UID / Shizuku / root | `/workspace` |
| Ubuntu 24.04 PRoot 工具 | 应用 UID / Shizuku / root（ARM64） | — |
| 实时会话系统浮窗 | ✅ | — |
| Amazon Bedrock 客户端 | — | ✅ |
| 动态 Cordis Agent 插件 | ✅ APK/dex | ✅ JAR |

各平台上的实际能力还取决于所选模型、设备能力以及用户授予的权限。

浏览器应用目标已移除。Android 与桌面端继续支持联网搜索、内嵌 Web 容器和可运行的 Web Artifact。

## 获取 kcode

带 Tag 的版本会自动将签名 Android APK、Windows MSI、macOS DMG 与 Linux DEB 发布到 [GitHub Releases](https://github.com/meteor149/kcode/releases)。

### 环境要求

- JDK 21；JVM 字节码目标为 Java 17
- Android 端需要 Android Studio 与 Android SDK 35（最低 Android API 35）
- 使用可选 Ubuntu 环境时，需要 ARM64 Android 设备，并在所选运行时位置保留至少 384 MiB 可用空间

请从项目根目录使用仓库自带的 Gradle Wrapper。Windows 用户请将 `./gradlew` 替换为 `gradlew.bat`。

### 桌面端

```bash
./gradlew :apps:desktopApp:run
```

### Android

启动 API 35 或更高版本的模拟器，或连接兼容设备，然后执行：

```bash
./gradlew :apps:androidApp:installDebug
```

Android 向 Agent 提供两个命令环境：`execute_shell_command` 使用 Android 的 `/system/bin/sh`；`execute_ubuntu_command` 会在首次调用时安装内置 Ubuntu 24.04 ARM64 根文件系统，并通过 PRoot 执行 GNU/Linux 工具。两者都遵循设置页选择的 Shell 模式：

- **应用**模式使用 kcode 的应用 UID 与私有 `/workspace`。
- **ADB** 模式要求通过 `adb` 启动 Shizuku，以 UID 2000 运行，并在 `/data/local/tmp/ai.meteor.kcode/ubuntu` 下维护独立运行时和工作区。
- **Root** 模式要求 `su` 正常授权，会校验真实 UID 0，并与应用模式共用运行时和工作区。

只有应用与 Root 模式会暴露常规的应用私有 Agent 工作区；ADB 模式的 `/workspace` 对以应用 UID 运行的 kcode 文件工具不可见。Root 与应用模式共用私有工作区，因此它创建的 Host 文件也可能保留应用模式之后无法读取的属主或权限。

Ubuntu Guest 会显示 PRoot 模拟的 Linux root，但对 Android 文件系统和设备的真实访问权限始终来自所选身份。它不是虚拟机，也不提供独立启动的 Linux 内核或 systemd。PRoot 本身不会授予内核权限；Root 模式拥有的额外 Host 能力来自已校验的 Android UID 0，并继续受设备 `su`、Capability 与 SELinux 策略约束。安装安全措施、限制、产物来源、校验值与第三方协议见 [Android Ubuntu 运行时](docs/android-ubuntu-runtime.md)。

#### 通过 ADB 配置供应商

先打开 kcode，再发送配置广播。[ADB 设置指南](docs/adb-settings.md)集中维护完整 PowerShell 脚本、参数和排查步骤。成功保存返回 `result=-1`，不能只看 adb 退出码。

## 首次使用

1. 打开**设置 → 大模型供应商**并选择服务。
2. 填写凭据，以及该服务要求的 Endpoint、Deployment 或 Region。
3. 返回会话，从输入区选择模型和 Temperature。
4. 进行普通对话、要求 Agent 使用工具、明确要求它并行派发子任务，或者使用 `/goal <目标>` 创建持久任务。
5. 如需自动化，请明确要求创建单次提醒或周期任务；Agent 会在当前会话中管理它。

各平台的凭据存储方式不同：

- Android 使用加密 MMKV，并通过 Android Keystore 保护其密钥。
- 桌面端将设置保存在应用数据目录；原生桌面密钥链支持仍在规划中。

## 安全模型

- 桌面提供位于应用私有存储中的虚拟 `/workspace`，拒绝路径穿越和符号链接逃逸。Skill 与 Artifact 资源同样受 Skill 包或托管工作区边界约束。
- Android 文件与媒体工具除了私有工作区外，还可以接受真实绝对路径，但仍受到 Android/Linux 文件权限与所选执行身份的限制。
- 全局工具权限门决定 kcode 是拒绝、询问还是直接执行工具。`Bypass` 只会跳过 kcode 自身的确认，不会绕过操作系统、浏览器、WebView、Keystore、Shizuku 或 root 管理器的权限控制。
- Android Shell 明确区分应用 UID、Shizuku/ADB shell 与 root 模式；权限来源不可用时会直接失败，不会静默切换到另一身份。
- Android Ubuntu 工具遵循同一个执行身份。应用与 root 模式共用应用私有运行时，ADB 模式使用 `/data/local/tmp` 下由 shell 身份持有的独立运行时；PRoot 模拟的 Guest root 本身不会获得 Android root 权限。
- Android 的 ADB 设置 Receiver 只接受来自持有系统 `DUMP` 权限调用方的显式广播；它会先校验完整更新，再写入常规加密设置存储，但 ADB 命令参数在执行期间仍对可信 Host 可见。
- 定时任务仅在应用进程可用时执行，会持久化下一次运行状态，并将每次执行放入独立会话。应把任务 Prompt 视为未来的 Agent 指令：它会使用执行时配置的模型，并继续受交互式回合相同的工具权限、网络和操作系统边界约束。
- Android 实时会话浮窗只会在后台仍有生成任务时显示，并需要系统“显示在其他应用上层”权限；关闭浮窗不会授予或撤销任何工具权限。
- 本地 Web 应用运行在隔离容器中，并在运行时申请敏感能力。远程网站不会获得 kcode 的本地原生能力桥。
- 保存 Artifact 必须得到用户明确确认，并使用经过路径校验、容量限制且支持失败回滚的存储流程。
- 请勿提交 API Key、`local.properties`、设备截图、生成的数据库或其他隐私数据。

发现安全问题时，请优先私下联系维护者，不要在公开 Issue 中披露可利用细节。

## 工程结构

工程指南和主题文档统一收录在 [文档索引](docs/README.md)。

```text
apps/
  androidApp/          Android 应用 Host
  desktopApp/          桌面 Compose Host 与分发配置
libraries/
  ui/                  可选基础组件、design 契约与矢量资源
plugins/
  api/                 共享插件 SDK：领域契约、Cordis 服务与扩展事件
  runtime/             插件树组合与生命周期所有权
  default-ui-api/      可选默认 UI 布局、侧栏、页面与主题协议
  bundle-native/       默认原生产品组合
  application/         默认 Compose 根界面、设置会话与产品编排
  ui-pages/            独立的聊天、Artifact、设置宿主与主题贡献
  ui-settings/         独立的语言、模型、搜索和 Shell 设置表单
  capability-providers/ 平台 fs 与 shell 服务适配
  native-execution/    原生 Shell、Ubuntu 与特权进程实现
  native-filesystem/   原生文件系统与技能工作区实现
  native-notifications/ 原生通知 Provider 与平台资源生命周期
  web-search-provider/ HTTP 搜索实现与客户端生命周期
  subagent-provider/   进程内 Agent 协调，与模型工具分离
  agent-loop/          Koog Loop Provider
  inventory|tools|...  每项核心插件能力一个 Gradle 模块
  filesystem|shell|... 每项平台工具 Consumer 一个 Gradle 模块
  platform-*/          Desktop 与 Android Loader/Provider 组装
docs/                  设计与工程文档
```

Android 与桌面端使用相同的 Room Schema 和 Bundled SQLite。

## 构建与测试

```bash
# 插件 SDK 的跨平台测试
./gradlew :plugins:api:allTests

# 所有可用的多平台测试套件
./gradlew allTests

# 桌面插件组合与外部 JAR 加载
./gradlew :plugins:platform-desktop:test

# 领域工具、搜索 Provider 与 Agent 协调
./gradlew :plugins:goal:desktopTest :plugins:schedule:desktopTest :plugins:subagents:desktopTest :plugins:subagent-provider:desktopTest :plugins:web-search-provider:desktopTest

# Android 外部 APK/dex 加载（需要 API 35+ 设备或模拟器）
./gradlew :plugins:platform-android:connectedDebugAndroidTest

# Android Debug APK
./gradlew :apps:androidApp:assembleDebug
```

桌面安装包可通过 `:apps:desktopApp` 下的 `packageMsi`、`packageDmg` 与 `packageDeb` 任务构建。带 Tag 的提交会自动打包 Android 和桌面发布产物。

## 致谢

kcode 的实现离不开开源社区长期共享的成果。谨向以下项目的维护者与贡献者致以诚挚感谢，尤其感谢这些为本项目提供核心能力或明确实现参考的项目：

| 项目 | 对 kcode 的帮助 |
| --- | --- |
| [Kotlin](https://github.com/JetBrains/kotlin)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) 与 [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 提供跨平台语言、结构化并发与序列化基础。 |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)、[Material 3 / AndroidX](https://github.com/androidx/androidx) 与 [Haze](https://github.com/chrisbanes/haze) | 支撑共享的自适应界面、设计基础与视觉效果。 |
| [Koog](https://github.com/JetBrains/koog) 与 [Ktor](https://github.com/ktorio/ktor) | 构成 Agent、工具、模型供应商、流式响应与网络访问的核心基础。 |
| [Room](https://github.com/androidx/androidx/tree/androidx-main/room) 与 [SQLite](https://www.sqlite.org/) | 支撑 Android 与桌面端的本地会话持久化。 |
| [MMKV](https://github.com/Tencent/MMKV) 与 [Shizuku](https://github.com/RikkaApps/Shizuku) | 分别支持移动端设置存储，以及 Android 上边界明确的 ADB shell 执行。 |
| [Operit](https://github.com/AAswordman/Operit)、[OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore)、[PRoot](https://github.com/proot-me/proot)、[PRoot-Distro](https://github.com/termux/proot-distro) 与 [Ubuntu](https://ubuntu.com/) | Operit 的运行时设计与 TerminalCore 的产物链路为 Android Ubuntu 实现提供了参考；随包 PRoot 二进制、Loader 和 Ubuntu 根文件系统的准确来源见[运行时说明](docs/android-ubuntu-runtime.md)与 [NOTICE](NOTICE)。 |

以上内容是重点致谢，并非完整的第三方软件清单，也不代表相关项目对 kcode 的背书或隶属关系。各项目继续受各自协议与归属条款约束；Gradle 依赖声明和随包 NOTICE 才是实现层面的权威记录。

## 参与贡献

欢迎提交 Issue 与 Pull Request。请先阅读 [AGENTS.md](AGENTS.md)，了解项目结构、代码规范、测试命令和 PR 要求。提交应保持职责单一、覆盖可观察行为；涉及 UI 时请附上前后对比截图或录屏。

项目的长期方向很明确：在不断扩展 Agent 自主性、工具能力和平台覆盖的同时，始终保持安静、精致的原生体验与清晰的用户控制。每一项能力都应真正融入产品，而不是简单堆叠在聊天界面上。

## 开源协议

Copyright 2026 The kcode Authors.

本项目基于 [Apache License 2.0](LICENSE) 开源。在遵守协议条款的前提下，你可以使用、修改和分发本项目，也可以将其用于商业用途。该协议包含明确的专利授权，并要求保留适用的版权、协议与 NOTICE 信息。第三方组件继续遵循各自的开源协议；kcode 名称与 Logo 的使用受 Apache-2.0 第 6 条商标条款约束。

版权归属信息请参阅 [NOTICE](NOTICE)。
