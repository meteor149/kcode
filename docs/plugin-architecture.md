# kcode Cordis 插件架构

Desktop JVM 与 Android 的 agent runtime 通过 Maven 依赖使用 Cordis，不要求本地检出 cordis-kotlin。Web 继续使用原 Koog 组装路径，不伪装成支持动态插件。此边界与 cordis-kotlin 当前动态 JAR/APK loader 的平台能力一致。

## 服务定义

| Cordis key | Kotlin Service Definition | Provider / Consumer |
|---|---|---|
| `tools` | `KcodeTools` | 文件、Shell、Web、Skill、Artifact 插件注册 `ToolRegistry`；Koog loop 读取快照。 |
| `systemPrompt` | `KcodeSystemPrompt` | 有序 `PromptSection` providers；Koog loop 组装当前 prompt。 |
| `llm` | `KcodeLlm` | `ModelAdapter` registry；内置 Koog provider 覆盖当前全部模型供应商，外部插件可按优先级替换。 |
| `interaction` | `KcodeInteraction` | 平台 provider 提供权限模式和审批回调；工具执行 consumer 使用。 |
| `skills` | `KcodeSkills` | 平台 SkillRuntime provider；agent loop 准备 turn，skill tool 插件消费同一 runtime。 |
| `continuations` | `KcodeContinuations` | 有序 continuation policy registry；subagent mailbox 与 goal round driver 是独立 providers。 |
| `subagents` | `KcodeSubagents` | coordinator factory Service Definition；当前 provider 是进程内 `MultiAgentCoordinator`。 |
| `agents` | `KcodeAgents` | `KoogAgentLoopPlugin` 是默认 provider；Compose UI 只消费 `ChatService`。 |
| `settings` | `KcodeSettings` | 可替换的 `AppSettingsStore` provider；搜索工具与 UI 消费同一服务。 |
| `history` | `KcodeHistory` | 可替换的 `ConversationHistoryRepository` provider；默认仍使用各平台数据库实现。 |
| `artifacts` | `KcodeArtifacts` | UI 和产物工具共享 repository；只读 provider 不暴露保存工具。 |
| `webContainers` | `KcodeWebContainers` | 平台 Web 容器 provider；工具 consumer 随服务重新绑定。 |
| `applicationUi` | `KcodeApplicationUi` | 整个应用的 Compose renderer provider；默认 renderer 调用现有 `KcodeApp`。 |
| `pluginInventory` | `KcodePluginInventory` | 记录内置和动态插件的 id、版本、来源、能力和状态。 |
| `loader` | cordis-kotlin `Loader` | Desktop `JvmModuleLoader`、Android `AndroidModuleLoader`。 |

`agent/pre-step` 是 waterfall：普通监听者必须调用 `next()`，policy 可以短路或改写输入。`tools/pre-execute` 和 `tools/post-execute` 也是 waterfall，分别用于拒绝调用和变换结果；权限检查与真实执行位于两者之间，不能靠 schema 隐藏绕过。`agent/turn-started` 与 `agent/turn-finished` 是 awaited parallel 通知。持久聊天事实仍由现有 `ConversationHistoryRepository` 负责；这些 live 事件不能代替持久记录。

## 内置插件树

启动顺序由服务依赖表达，运行时实际挂载：

1. `core.plugin-inventory`, `core.loader`, `core.tools`, `core.system-prompt`, `core.llm`, `core.continuations`。
2. `provider.llm.koog`, `provider.prompt.default`, `provider.skills.platform`, `provider.interaction.platform`, `provider.subagents.in-process`。
3. 通用 subagent、goal、schedule tool consumers，subagent/goal continuation providers，以及平台 feature consumers：filesystem、shell、web-container、web-search、skill、artifact；Android 另外拆分 system shell 与 Ubuntu shell。
4. `provider.agent-loop.koog`，依赖 tools、systemPrompt、llm、interaction 并提供 agents。

每项由独立 Cordis Fiber 拥有。工具和 prompt 注册返回 `Disposable` 并交给当前 effect；卸载 feature Fiber 后贡献立即从下一次快照消失。设置、历史、产物、Web 容器和默认 UI 也是独立 provider。runtime 关闭时取消并等待活跃回合，再按逆序 dispose，Android Activity 和 Desktop Window 都调用统一的 `KcodeAgentRuntime.close()`。

## Profile 与运行时装卸

`KcodePluginProfile` 是对默认 bundle 的声明式 patch：`includeDefaults = false` 可创建自定义或 headless 组合；`disabled` 禁用已配置的插件；`overrides` 按稳定 id 替换默认或平台 feature 实现。重复 id、重复 override、未知 override 和未知 disabled id 在挂载前失败。只有 inventory 与动态 loader bridge 属于固定 bootstrap，所有产品插件（包括 `core.tools`、默认 agent loop 和 UI）都可禁用、恢复和替换。

```kotlin
val profile = KcodePluginProfile(
    disabled = setOf("consumer.tools.goal", "consumer.tools.web-search"),
    overrides = listOf(myAgentLoopMount, myUiMount),
)
val runtime = createDesktopKoogChatRuntime(settingsStore, historyRepository, profile)
runtime.pluginManager?.setEnabled("consumer.tools.goal", true)
```

`KcodePluginRuntime.pluginManager` 统一管理内置和外部插件。`setEnabled()` 保留配置并装卸 Fiber；对内置插件调用 `uninstall()` 等同禁用，之后仍可重新启用。`replace(DynamicPluginSpec)` 可显式将内置实现替换成外部 artifact；卸载该 artifact 后，原内置配置仍保留为 Disabled，可重新启用。bootstrap id 不允许覆盖。`installed()` 只返回外部 artifact 清单；完整组合和当前状态通过 `diagnostics()` 读取。

进程内实现通过 `replacePlugin(KcodePluginMount)` 替换。失败时重新挂载原实现；这不是旧实例的状态迁移，provider 如有进程内状态，需自行定义恢复或持久化契约。外部 artifact 的后续替换仍走 Cordis HMR transaction。`ConfiguredPluginModuleLoader` 将配置绑定到 artifact generation，保留原配置 validator，确保替换时不会错误地继续使用旧 Fiber 配置；失败时恢复配置和 artifact 元数据。

没有 provider 的 consumer 进入 Pending，而不是伪报 Active；重新启用 provider 后 Cordis 重新绑定依赖。聊天服务使用固定 facade，每个新回合解析当前 `agents` provider。UI 从已提交的服务快照渲染，renderer 或所需服务更换后更新；无关工具装卸不会重建其 remember 状态。Artifact、Skill、Web 容器、搜索工具均从对应服务取得实现，不再捕获平台工厂中固定的 provider。

装卸请求串行执行。活跃 agent 回合期间拒绝 install、replace、uninstall 和 setEnabled，调用方先完成或取消回合再重试。因此不支持回合中途切换工具、模型或提示词；已有快照在回合结束前有效。runtime 关闭会取消活跃回合并等待清理；已关闭 runtime 拒绝新回合和插件变更。

## Gradle 模块边界

插件不再由 `shared` 的同一个 source set 编译。Gradle 模块与 Cordis 职责保持一一可审计的边界：

| 模块 | 职责 |
|---|---|
| `plugins:api` | 稳定 Service Definition、事件、插件 inventory 数据类型。 |
| `plugins:inventory`, `tools`, `system-prompt`, `llm`, `continuations`, `interaction`, `skills` | 对应核心服务及其默认 provider。 |
| `plugins:subagents`, `goal`, `schedule` | 对应领域工具 consumer 与 continuation provider。 |
| `plugins:agent-loop` | Koog agent provider，只通过 Service Definition 读取其它能力。 |
| `plugins:application` | 设置、历史、产物、Web 容器 providers 与默认 Compose renderer；使用公共契约，不包含平台存储实现。 |
| `plugins:filesystem`, `shell`, `web-container`, `web-search`, `skill-tools`, `artifact-tools` | 每项用户可见工具能力的独立 consumer 模块。 |
| `plugins:runtime` | 组合插件树、持有 Fiber，不实现具体能力。 |
| `plugins:platform-desktop`, `plugins:platform-android` | 平台工具实例、可信动态 loader 与最终 provider 组装。 |

`shared` 只保留跨平台 UI/domain、Koog agent primitive 和平台 primitive，不依赖任何插件实现模块。Desktop 入口位于 `apps:desktopApp`，Android 入口位于 `apps:androidApp`；两个 Host 都只依赖对应 `platform-*` 组装模块。

## 动态插件 ABI 与安装

动态入口类实现 `org.cordis.Plugin<C>`。插件编译时使用 `compileOnly` 依赖 `plugins:api`、cordis-kotlin loader 和 Koog 工具 API，不把这些宿主 API 打进 JAR/APK。UI 插件还必须使用与宿主兼容的 Kotlin/Compose 编译器，不打包 Compose runtime。宿主共享类型统一由 `SharedPluginApiPackages` 声明，覆盖服务契约使用的模型、聊天、持久化、Skill、UI 和 Koog 类型；插件自身实现和私有依赖保持 child-first 隔离。不要把插件实现放进共享 API namespace。

`DynamicPluginSpec` 是已建立信任后的安装描述：`id`、`version`、入口类、宿主私有目录内的 artifact path、可信 SHA-256、依赖插件、配置、Android package name 和能力集合。Controller 不下载文件、不决定发布者信任；调用方必须先完成下载、签名/发布者检查和原子安装。

Desktop 只接受受控目录内校验通过的 JAR；Android 只接受应用私有目录内校验通过的 APK/JAR/dex。依赖形成显式 class-loader 图。`install()` 先验证和 import，再创建 Loader Entry，任一步失败都会释放 staged module 且不发布 inventory。`replace()` 使用 Cordis HMR transaction：新一代所有 Fiber apply 成功后才 commit，失败时旧代继续运行。`uninstall()` 先 dispose Entry，再释放 class loader 和 inventory。

## 插件作者规则

- id 和 service key 稳定、非空且全树唯一；`install()` 不允许冲突，替换已配置的产品插件必须显式调用 `replace()`。插件配置在 apply 前验证，错误必须阻止加载。
- provider 与 consumer 分离；外部插件依赖 `KcodeTools`/`KcodeSystemPrompt`/`KcodeLlm` 等定义，不导入 Desktop/Android 实现类。
- 所有贡献由当前 `EffectScope.collect()` 持有，不保留无法撤销的全局注册。
- prompt section 使用稳定 id 和显式 order；tool contribution id 不得重复。
- 模型 adapter 用 `supports` 声明范围和显式 priority；同优先级按 id 确定性选择。
- 插件 artifact 必须不可变且 SHA-256 与可信元数据一致；替换发布为新文件，不原地修改运行中的文件。
- UI、模型输出或持久状态的新行为要有对应真实组合测试；动态加载还需覆盖错误校验、卸载和失败替换回滚。

## 验证与剩余边界

Desktop 组合测试覆盖默认配置、未知配置拒绝、内置禁用/恢复、依赖 Pending/恢复、聊天 provider 替换与失败恢复、活跃回合保护、关闭取消、headless 组合、设置 consumer 重新绑定、真实 Compose 重组，以及真实 JAR 覆盖内置、配置随 generation 更新和失败回滚。Android `AndroidPluginCompositionTest` 使用 instrumentation APK 作为真实 dex artifact，覆盖替换内置、禁用/启用、HMR 失败回滚和恢复内置；必须在 API 35+ 设备上运行 `:plugins:platform-android:connectedDebugAndroidTest` 才能确认设备行为。

目前 profile 是 Kotlin 配置，运行时变更不写入安装清单，重启后重新应用启动 profile。插件下载/发布者验证、安装清单持久化、管理界面、跨插件 ABI 版本协商、任意状态迁移和页面/设置项级 UI slot 仍待实现。默认 UI 整体可替换，但其内部页面尚未逐一拆为插件。文件系统和 Shell 工具可整体替换，尚未拆出独立的 provider-neutral fs/subprocess 服务。平台宿主仍负责 Activity/Window、系统授权、图片导出和调度生命周期桥接。会话持久化仍是 repository 契约，尚未改造成 Harness 风格的完整 session event log。
