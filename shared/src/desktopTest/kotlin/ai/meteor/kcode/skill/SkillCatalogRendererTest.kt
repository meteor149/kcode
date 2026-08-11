package ai.meteor.kcode.skill

import kotlin.test.Test
import kotlin.test.assertContains

class SkillCatalogRendererTest {
    @Test
    fun rendersSourceSpecificLocatorsAndAccessInstructions() {
        val catalog = SkillCatalog(
            entries = listOf(
                descriptor(
                    kind = SkillAuthorityKind.Host,
                    name = "local",
                    resource = "/workspace/.agents/skills/local/SKILL.md",
                ),
                descriptor(
                    kind = SkillAuthorityKind.Executor,
                    name = "container",
                    resource = "skill://linux/build/SKILL.md",
                ),
                descriptor(
                    kind = SkillAuthorityKind.Orchestrator,
                    name = "remote",
                    resource = "skill://plugin/deploy/SKILL.md",
                ),
            ),
            generation = "test",
        )

        val prompt = SkillCatalogRenderer.render(catalog)

        assertContains(prompt, "local: Example (file: /workspace/.agents/skills/local/SKILL.md)")
        assertContains(prompt, "container: Example (environment resource: skill://linux/build/SKILL.md)")
        assertContains(prompt, "remote: Example (orchestrator resource: skill://plugin/deploy/SKILL.md)")
        assertContains(prompt, "For a `file` locator, use `read_file`")
        assertContains(prompt, "call `skills_list` first")
    }

    private fun descriptor(
        kind: SkillAuthorityKind,
        name: String,
        resource: String,
    ): SkillDescriptor = SkillDescriptor(
        authority = SkillAuthority(kind, "test"),
        packageId = "package:$name",
        mainResource = resource,
        name = name,
        description = "Example",
        scope = SkillScope.User,
        displayPath = resource,
    )
}
