# DeepSeek Harness 插件接口设计与规范

本文总结本地参考仓库 `deepseek-harness` 在提交 `47f943859bef60e4160492346772ded9b24f765a` 上的插件架构。事实来源为该仓库的 `AGENTS.md`、`docs/architecture.md`、`docs/cordis-primer.md`、`docs/glossary.md`、`docs/subsystems/*`、`packages/README.md` 以及各分组 README。包清单覆盖该提交下所有带 `package.json` 的 Harness 包；精确字段和事件载荷仍以对应包的 README、导出类型与生成的事件目录为准。

## 1. 总体模型

Harness 的核心命题是“一切皆插件”。模型适配器、会话日志、系统提示词、工具注册表、agent 接口和默认 agent loop 都没有启动特权，而是由 Cordis Loader 挂载到同一个插件树。插件只通过稳定的 `ctx.<key>` 服务、类型化事件和可逆 effect 交互，组合层通过 profile、bundle 和 patch 决定实际实现。

一个可替换能力必须同时考虑三个角色：

- **Service Definition**：声明稳定服务键、调用接口、数据类型、错误和事件；消费者只依赖它。
- **Service Provider**：实现服务或向服务注册一个命名 provider；平台、供应商和沙箱差异停在这里。
- **Consumer**：通过 `inject` 获取服务，向模型暴露工具、向 UI 暴露视图或执行策略；不得反向依赖具体 provider。

角色在演进节奏不同时拆包。`shell` 是标准范例：`shell` 定义 `ctx.shell`，`bash-local`/`bash-sandbox`/`pwsh-local` 提供实现，`tool-bash`/`tool-pwsh` 消费服务并注册到 `ctx.tools`。

## 2. Cordis 插件接口

插件有两种导出形态，不能混用：

- 服务插件默认导出 `Service` 子类，由类声明稳定 `ctx` key 和生命周期。
- 函数插件只命名导出 `name`、`inject`、`Config`、`apply(ctx, config)`，不提供 default export。

`Context` 是服务容器和 effect 所有者。必须依赖用 `inject` 声明；必需服务通过注入后的 `ctx.<name>` 使用，可选服务用 `ctx.get(name)` 读取。加载顺序来自依赖满足关系，而不是手工排序。隔离 realm 用于同一插件树内的会话或 agent 私有实现；每 agent 的贡献注册到 `agent.ctx`，其可见范围和生命周期由同一 context 决定。

所有注册都是 effect：工具、提示词段、provider、listener、定时器和子插件必须经 `ctx.effect()`、`ctx.on()` 或返回 disposer 的 `register()` 安装。Fiber 卸载时按逆序撤销；注册表测试必须证明卸载后贡献消失。初始化失败必须回滚，动态替换只有整代插件全部成功才提交。

配置在 Loader 边界验证。部署可调参数必须是可验证的 `Config` 字段，不得藏在常量或 `run()` 内部的隐式默认值里；默认值应在拥有语义的 provider 的显式 `resolve(request): spec` 阶段产生。配置自身即可判断的错误在加载时失败，其余错误在最早可确定的位置失败。

## 3. 事件和生命周期规范

事件名和 dispatch mode 是公共 API：

| 模式 | 等待 | 顺序 | 返回值 | 用途 |
|---|---:|---|---:|---|
| `emit` | 否 | 注册顺序 | 否 | 同步观察通知 |
| `parallel` | 是 | 并行 | 否 | 多个异步观察者 |
| `serial` | 是 | 注册顺序 | 是 | 顺序决策或清理 |
| `waterfall` | 否/链式 | 中间件顺序 | 是 | 包装、改写、拦截 |

waterfall listener 必须调用 `next()` 才会委托给下游；不调用代表有意短路。事件通过声明合并保持可扩展，JSDoc 标注 `@mode` 和各 payload 参数。闭合联合必须按 discriminant 穷尽并以 `assertNever` 收尾；声明合并开放的联合保留有文档的默认分支。

默认 agent loop 的稳定流程为：`turn/start` → 输入认领 → `agent/pre-step` → `step/start` → durable user message → `agent/request` → `llm/stream` → assistant chunks/message → tool calls → `tools/pre-execute` → `tools/execute` → `tools/post-execute` → tool results → `step/end` → 必要时下一 step → `agent/turn-stopping` → `turn/end`。`agent/pre-step`、`agent/request`、`llm/stream` 和三个 `tools/*` 事件是 waterfall。

会话事件是追加日志中的持久事实；agent 事件描述进程内活动；能力事件连接 provider、policy 和 consumer。任何进入模型请求的内容都必须能从 session log 重建，即“model-visible ⟺ logged”。插件不能用 UI 状态、缓存或临时回调偷偷添加模型上下文。

## 4. 模块全目录与接口职责

下表按 `packages/<group>/<package>` 完整列出当前 Harness 包。分号前是组级接口职责，括号中标出主要服务键或注册目标。

| 分组 | 包 | 接口职责 |
|---|---|---|
| `acp` | `acp` | 自动化 ACP server；把 agent/session 能力映射到协议，不承担人机 UI。 |
| `api` | `gateway`, `remotes` | Typert 单次 RPC gateway（`typertGateway`/`remote`）与 Host/Client BFF 组装。 |
| `attachment` | `attachment`, `attachment-local` | 不可变附件引用与限制（`attachments`）；本地内容寻址存储 provider。 |
| `boot` | `app-boot`, `cmdline` | profile/bundle/patch 启动、树 settle、命令行交接（`cmdlineArgs`, `appExit`）。 |
| `bundle` | `base`, `headless`, `web-app` | 只负责可覆盖的插件配置层；分别提供公共核心、一次性无 UI、Web 应用组合。 |
| `client` | `connection`, `hmr`, `locale`, `modules`, `runtime`, `schema-form`, `ui-agent-preset`, `ui-attachment`, `ui-commands`, `ui-conversation`, `ui-deliverables`, `ui-directory-picker-browse`, `ui-directory-picker-native`, `ui-goal`, `ui-input-trigger`, `ui-jobs`, `ui-layout`, `ui-message-feedback`, `ui-model-selection`, `ui-permission-presets`, `ui-plan`, `ui-primitives`, `ui-settings`, `ui-settings-general`, `ui-settings-models`, `ui-settings-plugin-inventory`, `ui-settings-plugins`, `ui-sidebar`, `ui-skill`, `ui-slots`, `ui-subagent`, `ui-theme`, `ui-tool`, `ui-trajectory`, `ui-user-questions`, `ui-workflow-run`, `ui-workspace`, `web`, `web-react` | 浏览器端 shell、RPC、对象服务和 UI slot 插件；功能视图注册到 slot/renderer，不导入 Host provider。 |
| `code-runtime` | `code-runtime`, `code-runtime-worker-thread` | 代码执行 Service Definition（`codeRuntime`）、worker-thread provider；Code Mode consumer 位于 tools。 |
| `compaction` | `command-compact`, `compaction`, `compaction-basic`, `compaction-tool-result-pruner` | 压缩定义（`compaction`）、摘要 provider、无模型工具结果裁剪和 `/compact` consumer。 |
| `context` | `agent-instructions`, `session-reference`, `time-context`, `tmux-context` | 通过 prompt/session 事件添加可持久重建的请求上下文；跨会话解析器为 `sessionReferenceResolver`。 |
| `core` | `agent`, `agent-default-model`, `agent-loop`, `agent-tool-presentation`, `scope`, `session`, `system-prompt`, `tools` | 产品 API 主干：`agents`、默认模型、可替换 loop、工具展示、agent scope、`sessions`、`systemPrompt`、`tools`。 |
| `credentials` | `credentials`, `credentials-local` | 凭据引用定义（`credentials`）与 env/`.env` provider；配置只保存引用，不跨边界传明文。 |
| `e2b` | `e2b`, `fs-e2b`, `subprocess-e2b` | E2B sandbox 生命周期（`e2b`）及 `fs`/`subprocess` provider；上层 shell/LSP 无需分叉。 |
| `examples` | `acp-demo`, `agent-spine-demo`, `jsonrpc-demo` | 可运行的真实 Loader 组合和快照入口，不定义产品接口。 |
| `extensions` | `cordis-client-runner`, `cordis-host-runner`, `tool-cordis`, `ui-cordis` | 自修改：Host/Client 子树 runner、模型侧 Cordis 工具和 UI 管理 consumer。 |
| `feedback` | `command-feedback`, `message-feedback` | 人类消息反馈的 durable domain 与命令 consumer。 |
| `fs` | `fs`, `fs-local`, `fs-observation-policy`, `fs-sandbox`, `tool-fs`, `tool-fs-search`, `tool-str-replace-editor` | 文件系统定义（`fs`）、local/sandbox provider、观察策略，以及读写、搜索、精确编辑 consumers。 |
| `goal` | `command-goal`, `goal`, `goal-round-driver`, `tool-goal` | 同会话目标状态（`goals`）、自动 continuation driver、人类命令和模型工具。 |
| `guard` | `repeat-tool-reminder`, `timeout-policy` | 基于 agent/tools 扩展事件的循环卫生和工具超时 policy。 |
| `hooks` | `hook-protocol`, `hooks-claude-code`, `hooks-codex` | Claude Code/Codex hook wire protocol 和桥接 provider。 |
| `host` | `apiproxy`, `directory-picker`, `directory-picker-auto`, `directory-picker-browse`, `directory-picker-native`, `frontend-static`, `plugin-inventory`, `webserver` | Web Host API、目录选择 seam/providers、静态站点、Loader inventory 和 HTTP 路由服务。 |
| `identity` | `anonymous-user-id` | 共享匿名身份 provider，拥有稳定生成和存储规则。 |
| `interaction` | `commands`, `permission-presets`, `tool-ask-user`, `user-approval`, `user-questions` | 人类命令注册、权限预设、审批/提问 Service Definitions 与模型 consumer。 |
| `jobs` | `jobs`, `jobs-local`, `tool-jobs` | 后台任务定义（`jobs`）、本地 provider、collect/stop 等模型工具。 |
| `llm` | `llm`, `llm-deepseek`, `llm-pi-ai`, `llm-retry`, `token-meter` | 流式消息与 adapter registry（`llm`）、provider、重试 policy、独立 token 计量服务。 |
| `lsp` | `lsp`, `lsp-stdio`, `tool-lsp` | LSP 定义（`lsp`）、stdio provider 和模型 consumer；进程委托 `subprocess`。 |
| `mcp` | `mcp-client` | MCP client 生命周期与工具贡献适配。 |
| `plan` | `plan-mode` | 作为 durable session state 的计划模式和相关 UI/agent 事件。 |
| `preset` | `agent-presets`, `persona` | 每 session 的插件组合与 persona；用 isolate realm 构成独立 agent 能力集。 |
| `runtime-diagnostics` | `invariants` | 各包注册运行时 invariant installer 并报告真实事件/数据关系。 |
| `sandbox` | `sandbox`, `sandbox-local`, `sandbox-policy`, `sandbox-windows-acl` | 进程约束定义（`sandbox`）、平台 provider 与策略；只负责 argv/执行环境约束。 |
| `schedule` | `schedule` | session-local 定时 follow-up 的 durable 定义、触发和取消。 |
| `sdk` | `client`, `protocol`, `server` | JSON-RPC wire protocol、TypeScript client 和 Harness server plugin。 |
| `session` | `session-checkpoint-policy`, `session-persistence`, `session-persistence-jsonl`, `session-persistence-sqlite`, `session-projection`, `session-projection-cache`, `session-stats`, `session-telemetry`, `session-telemetry-otel`, `session-title`, `session-title-all-prompts-llm`, `session-title-first-prompt-llm`, `session-title-llm` | persistence/projection/title/telemetry 等完整 Service Definition + Provider 族；都从 session event stream 派生。 |
| `session-query` | `session-log-export`, `session-query`, `session-query-sqlite`, `tool-session-query` | 授权会话读取定义（`sessionQuery`）、SQLite FTS provider、导出 UI 和模型 consumer。 |
| `settings` | `settings`, `settings-file` | 命名空间、分层解析和 commit 定义（`settings`）；文件 provider 支持外部变更观察。 |
| `shell` | `bash-local`, `bash-sandbox`, `pwsh-local`, `pwsh-sandbox`, `shell`, `shell-env`, `tool-bash`, `tool-bash-persistent`, `tool-pwsh` | `shell` request/spec/result 定义、平台 providers、共享环境和模型 consumers。 |
| `skill` | `skill`, `skill-badge`, `skill-filesystem`, `tool-skill` | provider-neutral skill registry（`skills`）、内置/文件 provider、catalog/loader tool consumer。 |
| `spill` | `spill`, `spill-local`, `spill-policy` | 大输出存储定义（`spillStore`）、本地 provider、`tools/post-execute` 裁剪 policy。 |
| `storage` | `storage`, `storage-domain`, `storage-json`, `storage-sqlite` | 非会话存储 hub（`storage`）、typed form/domain 和 JSON/SQLite providers。 |
| `subagent` | `subagent`, `subagent-acp`, `subagent-claude-code`, `subagent-codex`, `subagent-dsh-sdk`, `subagent-fork-in-process`, `subagent-in-process-driver`, `subagent-spawn-in-process`, `tool-subagent`, `tool-subagent-control`, `tool-subagent-report` | 多 provider 子 agent registry（`subagents`）、进程内/外 providers、委派/控制/报告 consumers。 |
| `subprocess` | `subprocess`, `subprocess-local` | 一个执行世界的进程树、stdio、signal、PTY primitive 定义（`subprocess`）和本地 provider。 |
| `terminal` | `terminal`, `terminal-bash`, `tool-terminal` | owner-scoped persistent PTY registry（`terminals`）、shell provider 和六个模型工具。 |
| `test-support` | `acp-snapshot`, `agent-loop-testkit`, `client-runtime`, `llm-mock-server`, `llm-replay`, `loader-smoke` | 真实 Loader smoke、无密钥 replay/snapshot、loop 和 Client 测试设施。 |
| `todo` | `tool-todo` | 单 session todo durable state 与模型工具；无替换 provider，因此合为一包。 |
| `typert` | `generator`, `loader`, `protocol`, `registry` | 类型图生成、Loader 发现、wire protocol 和运行时 schema registry（`typert`）。 |
| `util` | `atomic-write`, `brand`, `home-paths`, `launch-environment`, `native-command`, `output-retention`, `timeout` | 无 Harness 依赖的 branded id、路径、原子写、原生命令、输出保留和 deadline 原语。 |
| `web` | `tool-web`, `web`, `web-fetch-http`, `web-search-deepseek`, `web-search-exa`, `web-search-perplexity` | search/fetch registry（`web`）、各 provider 和统一模型 consumer。 |
| `workflow` | `tool-ralph`, `tool-workflow`, `workflow`, `workflow-worker-thread` | workflow 定义（`workflowEngine`）、worker provider、通用 workflow 与固定 Ralph consumers。 |
| `workspace` | `workspace` | 持久 workspace 实体、realpath、session membership 和 registry（`workspaceRegistry`）。 |

## 5. 跨包硬性规范

- 扩展只依赖 Service Definition；只有 bundle/应用组合可以依赖具体 provider。
- 新行为挂在已记录的事件或 registry 上；若必须改 `agent-loop`，同步更新总架构和生命周期文档。
- opaque 跨边界 ID 使用 branded type，不裸用 `String`；进程、文件、JSON、数据库、模型 tool 参数和 wire 输入必须验证。
- 同进程强类型接口信任静态类型，不为不可能值增加兜底；真正的外部边界必须 fail loud。
- 运行时可调项进入 `Config`；协议常量、安全 invariant 和外部标准保持固定。
- registry 的 `register()` 返回 disposer，测试 Fiber dispose 后贡献消失；异步操作只能有一个明确生命周期 owner。
- 状态只在 commit point 发布；失败替换不得改变已发布代。缓存、projection、UI 和 telemetry 从同一权威事件源派生。
- 限制在完整结果可见处执行，覆盖包装和元数据；测试极小、恰好、超大单块和多字节边界。
- 每个产品可见插件需要真实 Loader 组合测试；model/user-visible 改动还需要无密钥可回放 snapshot。
- 每包 README 记录 purpose、API、extension points、Model Experience、限制；公共导出和事件有完整 JSDoc。

## 6. 对 kcode 的可复用结论

kcode 不需要逐字复制 TypeScript API，但必须复用这些稳定设计：能力三角色、稳定 service key、consumer 不依赖 provider、注册 effect 化、Loader 配置组合、动态代事务替换、事件 mode 固定、模型可见状态可重建、平台实现停在 provider。具体 Kotlin 映射见 [plugin-architecture.md](plugin-architecture.md)。
