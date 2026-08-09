package ai.meteor.kcode.skill

import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.meteor.kcode.AgentWorkspace

fun createWorkspaceSkillRuntime(
    workspace: AgentWorkspace,
    authorityId: String,
): SkillRuntime = SkillRuntime(
    providers = listOf(
        HostSkillProvider(
            workspace = workspace,
            roots = listOf(
                HostSkillRoot("/workspace/.agents/skills", SkillScope.User),
            ),
            authorityId = authorityId,
        ),
    ),
)

fun ToolRegistryBuilder.skillTools(runtime: SkillRuntime) {
    tool(SkillListTool(runtime))
    tool(SkillReadTool(runtime))
}
