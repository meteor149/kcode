package ai.meteor.kcode.plugin.skills

import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.skill.SkillScope

fun createWorkspaceSkillRuntime(
    workspace: AgentWorkspace,
    authorityId: String,
): SkillRuntime = createSkillRuntime(
    providers = listOf(
        HostSkillProvider(
            workspace = workspace,
            roots = listOf(
                HostSkillRoot("/workspace/.agents/skills", SkillScope.User),
                HostSkillRoot("/workspace/.kcode/skills", SkillScope.System),
            ),
            authorityId = authorityId,
        ),
    ),
)
