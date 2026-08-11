package ai.meteor.kcode.skill

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

class SkillListTool(
    private val runtime: SkillRuntime,
) : SimpleTool<SkillListTool.Args>(
    argsType = typeToken<Args>(),
    name = "skills_list",
    description = "Lists enabled model-visible skills and exact handles needed to read non-host skill resources.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Reload the bounded host catalog before listing")
        val forceReload: Boolean = false,
    )

    override suspend fun execute(args: Args): String {
        val catalog = runtime.catalog(args.forceReload)
        return catalog.entries.asSequence()
            .filter { it.enabled && it.promptVisible }
            .joinToString("\n") { skill ->
                "name=${skill.name}; authority=${skill.authority.kind.name.lowercase()}:${skill.authority.id}; " +
                    "package=${skill.packageId}; main_resource=${skill.mainResource}; description=${skill.description}"
            }
            .ifBlank { "No enabled skills are available." }
    }
}

class SkillReadTool(
    private val runtime: SkillRuntime,
) : SimpleTool<SkillReadTool.Args>(
    argsType = typeToken<Args>(),
    name = "skills_read",
    description = "Reads one UTF-8 executor or orchestrator skill resource using exact handles returned by skills_list. Host file locators should use read_file.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Authority kind: host, executor, or orchestrator")
        val authorityKind: String,
        @property:LLMDescription("Exact authority id")
        val authorityId: String,
        @property:LLMDescription("Exact opaque skill package handle")
        val packageId: String,
        @property:LLMDescription("Exact main resource or a package-contained resource path")
        val resourceId: String,
    )

    override suspend fun execute(args: Args): String {
        val kind = SkillAuthorityKind.entries.singleOrNull { it.name.equals(args.authorityKind, ignoreCase = true) }
            ?: error("Unknown skill authority kind")
        return runtime.read(
            SkillReadRequest(
                authority = SkillAuthority(kind, args.authorityId),
                packageId = args.packageId,
                resourceId = args.resourceId,
            ),
        ).contents
    }
}
