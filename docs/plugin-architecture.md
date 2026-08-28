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
| `pluginInventory` | `KcodePluginInventory` | 记录内置和动态插件的 id、版本、来源、能力和状态。 |
| `loader` | cordis-kotlin `Loader` | Desktop `JvmModuleLoader`、Android `AndroidModuleLoader`。 |

`agent/pre-step` 是 waterfall：普通监听者必须调用 `next()`，policy 可以短路或改写输入。`tools/pre-execute` 和 `tools/post-execute` 也是 waterfall，分别用于拒绝调用和变换结果；权限检查与真实执行位于两者之间，不能靠 schema 隐藏绕过。`agent/turn-started` 与 `agent/turn-finished` 是 awaited parallel 通知。持久聊天事实仍由现有 `ConversationHistoryRepository` 负责；这些 live 事件不能代替持久记录。

## 内置插件树

启动顺序由服务依赖表达，运行时实际挂载：

1. `core.plugin-inventory`, `core.loader`, `core.tools`, `core.system-prompt`, `core.llm`, `core.continuations`。
2. `provider.llm.koog`, `provider.prompt.default`, `provider.skills.platform`, `provider.interaction.platform`, `provider.subagents.in-process`。
3. 通用 subagent、goal、schedule tool consumers，subagent/goal continuation providers，以及平台 feature consumers：filesystem、shell、web-container、web-search、skill、artifact；Android 另外拆分 system shell 与 Ubuntu shell。
4. `provider.agent-loop.koog`，依赖 tools、systemPrompt、llm、interaction 并提供 agents。

每项由独立 Cordis Fiber 拥有。工具和 prompt 注册返回 `Disposable` 并交给当前 effect；卸载 feature Fiber 后贡献立即从下一次快照消失。runtime 关闭时按逆序 dispose，Android Activity 和 Desktop Window 都调用统一的 `KcodeAgentRuntime.close()`。

## Gradle 模块边界

插件不再由 `shared` 的同一个 source set 编译。Gradle 模块与 Cordis 职责保持一一可审计的边界：

| 模块 | 职责 |
|---|---|
| `plugins:api` | 稳定 Service Definition、事件、插件 inventory 数据类型。 |
| `plugins:inventory`, `tools`, `system-prompt`, `llm`, `continuations`, `interaction`, `skills` | 对应核心服务及其默认 provider。 |
| `plugins:subagents`, `goal`, `schedule` | 对应领域工具 consumer 与 continuation provider。 |
| `plugins:agent-loop` | Koog agent provider，只通过 Service Definition 读取其它能力。 |
| `plugins:filesystem`, `shell`, `web-container`, `web-search`, `skill-tools`, `artifact-tools` | 每项用户可见工具能力的独立 consumer 模块。 |
| `plugins:runtime` | 组合插件树、持有 Fiber，不实现具体能力。 |
| `plugins:platform-desktop`, `plugins:platform-android` | 平台工具实例、可信动态 loader 与最终 provider 组装。 |

`shared` 只保留跨平台 UI/domain、Koog agent primitive 和平台 primitive，不依赖任何插件实现模块。Desktop 入口位于 `apps:desktopApp`，Android 入口位于 `apps:androidApp`；两个 Host 都只依赖对应 `platform-*` 组装模块。

## 动态插件 ABI 与安装

动态入口类实现 `org.cordis.Plugin<C>`。插件编译时使用 `compileOnly` 依赖 `plugins:api`、cordis-kotlin loader 和 Koog 工具 API，不把这些宿主 API 打进 JAR/APK；只有确实使用模型或权限公共类型时才额外依赖 `shared`。宿主共享包包括 `ai.meteor.kcode.plugin.api`、模型/权限公共类型与 `ai.koog`，插件自身实现和私有依赖保持 child-first 隔离。

`DynamicPluginSpec` 是已建立信任后的安装描述：`id`、`version`、入口类、宿主私有目录内的 artifact path、可信 SHA-256、依赖插件、配置、Android package name 和能力集合。Controller 不下载文件、不决定发布者信任；调用方必须先完成下载、签名/发布者检查和原子安装。

Desktop 只接受受控目录内校验通过的 JAR；Android 只接受应用私有目录内校验通过的 APK/JAR/dex。依赖形成显式 class-loader 图。`install()` 先验证和 import，再创建 Loader Entry，任一步失败都会释放 staged module 且不发布 inventory。`replace()` 使用 Cordis HMR transaction：新一代所有 Fiber apply 成功后才 commit，失败时旧代继续运行。`uninstall()` 先 dispose Entry，再释放 class loader 和 inventory。

## 插件作者规则

- id 和 service key 稳定、非空且全树唯一；动态插件不得覆盖内置或已挂载插件的 inventory id。插件配置在 apply 前验证，错误必须阻止加载。
- provider 与 consumer 分离；外部插件依赖 `KcodeTools`/`KcodeSystemPrompt`/`KcodeLlm` 等定义，不导入 Desktop/Android 实现类。
- 所有贡献由当前 `EffectScope.collect()` 持有，不保留无法撤销的全局注册。
- prompt section 使用稳定 id 和显式 order；tool contribution id 不得重复。
- 模型 adapter 用 `supports` 声明范围和显式 priority；同优先级按 id 确定性选择。
- 插件 artifact 必须不可变且 SHA-256 与可信元数据一致；替换发布为新文件，不原地修改运行中的文件。
- UI、模型输出或持久状态的新行为要有对应真实组合测试；动态加载还需覆盖错误校验、卸载和失败替换回滚。
