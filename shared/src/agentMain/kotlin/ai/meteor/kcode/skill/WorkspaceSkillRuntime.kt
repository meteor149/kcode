package ai.meteor.kcode.skill

import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.meteor.kcode.AgentWorkspace
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun createWorkspaceSkillRuntime(
    workspace: AgentWorkspace,
    authorityId: String,
): SkillRuntime = SkillRuntime(
    providers = listOf(
        MaterializingSkillProvider(
            workspace = workspace,
            delegate = HostSkillProvider(
                workspace = workspace,
                roots = listOf(
                    HostSkillRoot("/workspace/.agents/skills", SkillScope.User),
                    HostSkillRoot("/workspace/.kcode/skills", SkillScope.System),
                ),
                authorityId = authorityId,
            ),
        ),
    ),
)

fun ToolRegistryBuilder.skillTools(runtime: SkillRuntime) {
    tool(SkillListTool(runtime))
    tool(SkillReadTool(runtime))
}

private class MaterializingSkillProvider(
    private val workspace: AgentWorkspace,
    private val delegate: SkillProvider,
) : SkillProvider by delegate {
    private val installMutex = Mutex()
    private var installed = false

    override suspend fun catalog(forceReload: Boolean): SkillCatalog {
        ensureBuiltins()
        return delegate.catalog(forceReload)
    }

    override suspend fun read(request: SkillReadRequest): SkillReadResult {
        ensureBuiltins()
        return delegate.read(request)
    }

    private suspend fun ensureBuiltins() = installMutex.withLock {
        if (installed) return@withLock
        workspace.writeText(BuiltinWebAppSkillPath, BuiltinWebAppSkill)
        installed = true
    }
}

internal const val BuiltinWebAppSkillPath = "/workspace/.kcode/skills/web-app-builder/SKILL.md"

internal val BuiltinWebAppSkill = """
    ---
    name: kcode-web-app-builder
    description: Build, run, inspect, debug, and optionally save responsive Web applications in Kcode.
    ---

    # Web application workflow

    Use this skill whenever the user asks to create, change, or debug a browser application.

    1. Create the project in its own directory under `/workspace`. Keep every HTML, CSS, JavaScript, image, and data file inside that directory. Do not start an HTTP server.
    2. Prefer a small dependency-free application unless the request genuinely needs a framework. The entry point must be an `.html` file, normally `index.html`.
    3. Design mobile-first: use responsive sizing, safe-area insets, touch targets of at least 44 CSS pixels, readable type, sensible focus states, and layouts that remain usable on narrow screens. Also support desktop widths.
    4. After writing the files, call `preview_web_app` with the `/workspace/...` entry path. Use `inspect_web_container`, `interact_web_container`, `get_web_console`, and `screenshot_web_container` to exercise the real UI. Fix console errors, broken interactions, overflow, illegible contrast, and important visual defects, then repeat the checks.
    5. When the requested application is complete and verified, ask the user whether to save it as an artifact. Stop and wait for an explicit answer. Do not infer consent from the original build request.
    6. Only after the user explicitly agrees, call `save_web_artifact` with `userConfirmed=true`, a stable lowercase id, a useful name and description, the project directory, and its HTML entry point. Report the saved artifact id and explain that it is available from the Artifacts page.

    Saving copies the complete project directory into Kcode's managed artifact resources and updates the artifact manifest. Never edit the manifest directly when `save_web_artifact` is available.
""".trimIndent()
