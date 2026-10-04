# DeepSeek Harness plugin interface design and specification

This document summarizes the plugin architecture of the local `deepseek-harness` reference
repository at commit `47f943859bef60e4160492346772ded9b24f765a`. Its sources are that repository's
`AGENTS.md`, `docs/architecture.md`, `docs/cordis-primer.md`, `docs/glossary.md`,
`docs/subsystems/*`, `packages/README.md`, and group READMEs. The package catalog covers all
Harness packages with a `package.json` at that commit. Consult the corresponding package
READMEs, exported types, and generated event catalog for exact fields and event payloads.

See [Harness reserved APIs](harness-reserved-api.md) for Kotlin contracts, service keys, and
lifecycle mappings of key subsystems that kcode has not implemented. Available definitions
do not imply that the Native bundle supplies those capabilities.

## 1. Overall model

Harness treats everything as a plugin. Model adapters, session logs, system prompts, tool
registries, agent interfaces, and the default agent loop have no startup privileges.
The Cordis Loader mounts them in the same plugin tree. Plugins interact only through stable
`ctx.<key>` services, typed events, and reversible effects. Profiles, bundles, and patches
let the composition layer select implementations.

Every replaceable capability has three roles:

- **Service Definition**: declares stable service keys, call interfaces, data types, errors, and events. Consumers depend only on this role.
- **Service Provider**: implements a service or registers a named provider. Platform, vendor, and sandbox differences remain here.
- **Consumer**: obtains services through `inject` and exposes model tools, UI views, or policy. It must not depend on concrete providers.

Separate roles into packages when their evolution differs. `shell` is the standard example:
`shell` defines `ctx.shell`; `bash-local`/`bash-sandbox`/`pwsh-local` provide implementations;
`tool-bash`/`tool-pwsh` consume the service and register tools with `ctx.tools`.

## 2. Cordis plugin interfaces

Plugins have two mutually exclusive export forms:

- Service plugins default-export a `Service` subclass. The class declares a stable `ctx` key and lifecycle.
- Function plugins use named exports only: `name`, `inject`, `Config`, and `apply(ctx, config)`. They have no default export.

`Context` is both the service container and effect owner. Declare required dependencies with
`inject`, access required services through injected `ctx.<name>`, and read optional services
with `ctx.get(name)`. Dependency satisfaction determines load order rather than manual sorting.
Isolated realms provide session- or agent-private implementations within the same tree.
Per-agent contributions register with `agent.ctx`, which determines both visibility and lifetime.

Every registration is an effect. Install tools, prompt sections, providers, listeners, timers,
and child plugins through `ctx.effect()`, `ctx.on()`, or `register()` returning a disposer.
Fiber unloading revokes them in reverse order. Registry tests must prove contributions
vanish after unloading. Failed initialization rolls back; dynamic replacement commits only
when every plugin in the candidate generation succeeds.

Validate configuration at the Loader boundary. Deployable parameters must be validated
`Config` fields rather than hidden constants or implicit defaults in `run()`. Defaults belong
to the semantic owner's explicit provider `resolve(request): spec` phase. Reject errors
that configuration alone determines at load time, and other errors at the earliest point
where they can be established.

## 3. Events and lifecycle conventions

Event names and dispatch modes are public API:

| Mode | Awaited | Order | Return value | Purpose |
| --- | --- | --- | --- | --- |
| `emit` | No | Registration order | No | Synchronous observation |
| `parallel` | Yes | Parallel | No | Multiple asynchronous observers |
| `serial` | Yes | Registration order | Yes | Sequential decisions or cleanup |
| `waterfall` | No/chained | Middleware order | Yes | Wrapping, rewriting, interception |

Waterfall listeners delegate downstream only by calling `next()`; omitting it intentionally
short-circuits. Declaration merging keeps events extensible. JSDoc records `@mode` and every
payload parameter. Exhaust closed unions by discriminant and finish with `assertNever`.
Open unions extended through declaration merging retain a documented default branch.

The default agent loop follows: `turn/start` → input claim → `agent/pre-step` → `step/start`
→ durable user message → `agent/request` → `llm/stream` → assistant chunks/message → tool calls
→ `tools/pre-execute` → `tools/execute` → `tools/post-execute` → tool results → `step/end`
→ another step when needed → `agent/turn-stopping` → `turn/end`. `agent/pre-step`,
`agent/request`, `llm/stream`, and the three `tools/*` events use waterfall dispatch.

Session events are durable facts in an append-only log; agent events describe in-process
activity; capability events connect providers, policy, and consumers. All model request
content must be reconstructable from the session log: "model-visible ⟺ logged". Plugins
must not silently add model context through UI state, caches, or temporary callbacks.

## 4. Complete package catalog and interface responsibilities

The table lists all Harness packages by `packages/<group>/<package>`. It describes group
responsibilities and identifies major service keys or registration targets in parentheses.

| Group | Packages | Interface responsibilities |
| --- | --- | --- |
| `acp` | `acp` | Automated ACP server; maps agent/session capabilities to the protocol and does not own human-facing UI. |
| `api` | `gateway`, `remotes` | Typert single-call RPC gateway (`typertGateway`/`remote`) and Host/Client BFF assembly. |
| `attachment` | `attachment`, `attachment-local` | Immutable attachment references and limits (`attachments`); local content-addressed storage provider. |
| `boot` | `app-boot`, `cmdline` | Profile/bundle/patch startup, tree settling, and command-line handoff (`cmdlineArgs`, `appExit`). |
| `bundle` | `base`, `headless`, `web-app` | Overridable plugin configuration layers only: shared core, one-shot headless execution, and Web application composition. |
| `client` | `connection`, `hmr`, `locale`, `modules`, `runtime`, `schema-form`, `ui-agent-preset`, `ui-attachment`, `ui-commands`, `ui-conversation`, `ui-deliverables`, `ui-directory-picker-browse`, `ui-directory-picker-native`, `ui-goal`, `ui-input-trigger`, `ui-jobs`, `ui-layout`, `ui-message-feedback`, `ui-model-selection`, `ui-permission-presets`, `ui-plan`, `ui-primitives`, `ui-settings`, `ui-settings-general`, `ui-settings-models`, `ui-settings-plugin-inventory`, `ui-settings-plugins`, `ui-sidebar`, `ui-skill`, `ui-slots`, `ui-subagent`, `ui-theme`, `ui-tool`, `ui-trajectory`, `ui-user-questions`, `ui-workflow-run`, `ui-workspace`, `web`, `web-react` | Browser shell, RPC, object services, and UI slot plugins; feature views register with slots/renderers and do not import Host providers. |
| `code-runtime` | `code-runtime`, `code-runtime-worker-thread` | Code execution service definition (`codeRuntime`) and worker-thread provider; the Code Mode consumer lives in tools. |
| `compaction` | `command-compact`, `compaction`, `compaction-basic`, `compaction-tool-result-pruner` | Compaction definition (`compaction`), summary provider, model-free tool-result pruning, and `/compact` consumer. |
| `context` | `agent-instructions`, `session-reference`, `time-context`, `tmux-context` | Adds durably reconstructable request context through prompt/session events; the cross-session resolver is `sessionReferenceResolver`. |
| `core` | `agent`, `agent-default-model`, `agent-loop`, `agent-tool-presentation`, `scope`, `session`, `system-prompt`, `tools` | Product API backbone: `agents`, default model, replaceable loop, tool presentation, agent scope, `sessions`, `systemPrompt`, and `tools`. |
| `credentials` | `credentials`, `credentials-local` | Credential reference definition (`credentials`) and env/`.env` provider; configuration stores references and does not pass plaintext across boundaries. |
| `e2b` | `e2b`, `fs-e2b`, `subprocess-e2b` | E2B sandbox lifecycle (`e2b`) and `fs`/`subprocess` providers; higher-level shell/LSP consumers do not need separate implementations. |
| `examples` | `acp-demo`, `agent-spine-demo`, `jsonrpc-demo` | Runnable real Loader compositions and snapshot entry points; no product interface definitions. |
| `extensions` | `cordis-client-runner`, `cordis-host-runner`, `tool-cordis`, `ui-cordis` | Self-modification: Host/Client subtree runners, model-facing Cordis tools, and UI management consumers. |
| `feedback` | `command-feedback`, `message-feedback` | Durable domain for human message feedback and command consumer. |
| `fs` | `fs`, `fs-local`, `fs-observation-policy`, `fs-sandbox`, `tool-fs`, `tool-fs-search`, `tool-str-replace-editor` | Filesystem definition (`fs`), local/sandbox providers, observation policy, and read/write, search, and exact-edit consumers. |
| `goal` | `command-goal`, `goal`, `goal-round-driver`, `tool-goal` | Goals within a session (`goals`), automatic continuation driver, human commands, and model tools. |
| `guard` | `repeat-tool-reminder`, `timeout-policy` | Loop hygiene and tool timeout policy using agent/tools extension events. |
| `hooks` | `hook-protocol`, `hooks-claude-code`, `hooks-codex` | Claude Code/Codex hook wire protocols and bridge providers. |
| `host` | `apiproxy`, `directory-picker`, `directory-picker-auto`, `directory-picker-browse`, `directory-picker-native`, `frontend-static`, `plugin-inventory`, `webserver` | Web Host API, directory selection interfaces/providers, static frontend, Loader inventory, and HTTP routing services. |
| `identity` | `anonymous-user-id` | Shared anonymous identity provider, owning stable generation and storage rules. |
| `interaction` | `commands`, `permission-presets`, `tool-ask-user`, `user-approval`, `user-questions` | Human command registration, permission presets, approval/question service definitions, and model consumers. |
| `jobs` | `jobs`, `jobs-local`, `tool-jobs` | Background job definition (`jobs`), local provider, and model tools such as collect/stop. |
| `llm` | `llm`, `llm-deepseek`, `llm-pi-ai`, `llm-retry`, `token-meter` | Streaming messages and adapter registry (`llm`), providers, retry policy, and independent token metering. |
| `lsp` | `lsp`, `lsp-stdio`, `tool-lsp` | LSP definition (`lsp`), stdio provider, and model consumer; delegates processes to `subprocess`. |
| `mcp` | `mcp-client` | MCP client lifecycle and tool contribution adaptation. |
| `plan` | `plan-mode` | Plan mode as durable session state, with related UI/agent events. |
| `preset` | `agent-presets`, `persona` | Per-session plugin composition and persona; isolated realms form independent agent capability sets. |
| `runtime-diagnostics` | `invariants` | Packages register runtime invariant installers and report actual event/data relationships. |
| `sandbox` | `sandbox`, `sandbox-local`, `sandbox-policy`, `sandbox-windows-acl` | Process confinement definition (`sandbox`), platform providers, and policy; owns only argv/execution environment constraints. |
| `schedule` | `schedule` | Durable definition, triggering, and cancellation of session-local scheduled follow-ups. |
| `sdk` | `client`, `protocol`, `server` | JSON-RPC wire protocol, TypeScript client, and Harness server plugin. |
| `session` | `session-checkpoint-policy`, `session-persistence`, `session-persistence-jsonl`, `session-persistence-sqlite`, `session-projection`, `session-projection-cache`, `session-stats`, `session-telemetry`, `session-telemetry-otel`, `session-title`, `session-title-all-prompts-llm`, `session-title-first-prompt-llm`, `session-title-llm` | Complete service definition/provider families for persistence, projections, titles, and telemetry, all derived from the session event stream. |
| `session-query` | `session-log-export`, `session-query`, `session-query-sqlite`, `tool-session-query` | Authorized session reading definition (`sessionQuery`), SQLite FTS provider, export UI, and model consumers. |
| `settings` | `settings`, `settings-file` | Namespace, layered resolution, and commit definition (`settings`); file provider supports external change observation. |
| `shell` | `bash-local`, `bash-sandbox`, `pwsh-local`, `pwsh-sandbox`, `shell`, `shell-env`, `tool-bash`, `tool-bash-persistent`, `tool-pwsh` | `shell` request/spec/result definition, platform providers, shared environment, and model consumers. |
| `skill` | `skill`, `skill-badge`, `skill-filesystem`, `tool-skill` | Provider-neutral skill registry (`skills`), built-in/filesystem providers, and catalog/loader tool consumers. |
| `spill` | `spill`, `spill-local`, `spill-policy` | Large-output storage definition (`spillStore`), local provider, and `tools/post-execute` truncation policy. |
| `storage` | `storage`, `storage-domain`, `storage-json`, `storage-sqlite` | Non-session storage hub (`storage`), typed form/domain, and JSON/SQLite providers. |
| `subagent` | `subagent`, `subagent-acp`, `subagent-claude-code`, `subagent-codex`, `subagent-dsh-sdk`, `subagent-fork-in-process`, `subagent-in-process-driver`, `subagent-spawn-in-process`, `tool-subagent`, `tool-subagent-control`, `tool-subagent-report` | Multi-provider subagent registry (`subagents`), in-process/external providers, and delegation/control/reporting consumers. |
| `subprocess` | `subprocess`, `subprocess-local` | Process tree, stdio, signal, and PTY primitive definition (`subprocess`) for one execution world, with a local provider. |
| `terminal` | `terminal`, `terminal-bash`, `tool-terminal` | Owner-scoped persistent PTY registry (`terminals`), shell provider, and six model tools. |
| `test-support` | `acp-snapshot`, `agent-loop-testkit`, `client-runtime`, `llm-mock-server`, `llm-replay`, `loader-smoke` | Real Loader smoke tests, credential-free replay/snapshots, and loop/Client test facilities. |
| `todo` | `tool-todo` | Durable per-session todo state and model tools; combined in one package because there is no replaceable provider. |
| `typert` | `generator`, `loader`, `protocol`, `registry` | Type graph generation, Loader discovery, wire protocol, and runtime schema registry (`typert`). |
| `util` | `atomic-write`, `brand`, `home-paths`, `launch-environment`, `native-command`, `output-retention`, `timeout` | Branded IDs, paths, atomic writes, native commands, output retention, and deadline primitives without Harness dependencies. |
| `web` | `tool-web`, `web`, `web-fetch-http`, `web-search-deepseek`, `web-search-exa`, `web-search-perplexity` | Search/fetch registry (`web`), individual providers, and a unified model consumer. |
| `workflow` | `tool-ralph`, `tool-workflow`, `workflow`, `workflow-worker-thread` | Workflow definition (`workflowEngine`), worker provider, generic workflow consumers, and fixed Ralph consumers. |
| `workspace` | `workspace` | Durable workspace entities, realpath, session membership, and registry (`workspaceRegistry`). |

## 5. Cross-package requirements

- Extensions depend only on service definitions. Only bundles/application compositions may depend on concrete providers.
- Attach new behavior to documented events or registries. If `agent-loop` must change, update overall architecture and lifecycle documentation together.
- Use branded types for opaque cross-boundary IDs instead of bare `String` values. Validate process, file, JSON, database, model tool argument, and wire inputs.
- Trust static types in strongly typed in-process interfaces rather than adding fallbacks for impossible values. Fail explicitly at genuine external boundaries.
- Put runtime configuration in `Config`. Keep protocol constants, security invariants, and external standards fixed.
- Registry `register()` returns a disposer. Test that Fiber disposal removes contributions. Every asynchronous operation has one explicit lifecycle owner.
- Publish state only at commit points. Failed replacement must not change the published generation. Derive caches, projections, UI, and telemetry from the same authoritative event source.
- Enforce limits where the complete result is visible, including wrappers and metadata. Test tiny, exact-limit, oversized single-block, and multibyte boundaries.
- Every product-visible plugin needs real Loader composition tests. Model/user-visible changes also need credential-free replayable snapshots.
- Each package README records purpose, API, extension points, Model Experience, and limitations. Public exports and events have complete JSDoc.

## 6. Design lessons for kcode

kcode does not need to copy the TypeScript API verbatim, but should preserve these stable
design principles: three capability roles, stable service keys, consumers independent of
providers, effect-owned registrations, Loader configuration composition, transactional
replacement of dynamic generations, fixed event modes, reconstructable model-visible state,
and platform implementations confined to providers. See
[plugin architecture](plugin-architecture.md) for the Kotlin mapping.
