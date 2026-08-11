package ai.meteor.kcode.skill

import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.AgentWorkspaceEntry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SkillRuntimeTest {
    @Test
    fun discoversMetadataWithoutInjectingBodiesAndExplicitlyLoadsUniqueMention() = runTest {
        val workspace = MemoryWorkspace(
            mapOf(
                "/workspace/.agents/skills/writer/SKILL.md" to "---\nname: writer\ndescription: Draft concise prose\n---\nSECRET BODY",
                "/workspace/.agents/skills/writer/references/style.md" to "Reference body",
            ),
        )
        val runtime = runtime(workspace)

        val catalogOnly = runtime.prepareTurn("Help me organize this")
        assertContains(catalogOnly.catalogInstructions, "writer")
        assertContains(
            catalogOnly.catalogInstructions,
            "(file: /workspace/.agents/skills/writer/SKILL.md)",
        )
        assertContains(catalogOnly.catalogInstructions, "For a `file` locator, use `read_file`")
        assertFalse(catalogOnly.catalogInstructions.contains("SECRET BODY"))
        assertTrue(catalogOnly.selectedSkillFragments.isEmpty())

        val selected = runtime.prepareTurn("Use ${'$'}writer for this")
        assertEquals(1, selected.selectedSkillFragments.size)
        assertContains(selected.selectedSkillFragments.single(), "SECRET BODY")
        assertContains(
            selected.selectedSkillFragments.single(),
            "<path>/workspace/.agents/skills/writer/SKILL.md</path>",
        )
    }

    @Test
    fun ambiguousPlainNameDoesNotSelectEitherSkill() = runTest {
        val workspace = MemoryWorkspace(
            mapOf(
                "/workspace/.agents/skills/one/SKILL.md" to "---\nname: duplicate\ndescription: First\n---\nONE",
                "/workspace/.agents/skills/two/SKILL.md" to "---\nname: duplicate\ndescription: Second\n---\nTWO",
            ),
        )

        val turn = runtime(workspace).prepareTurn("Use ${'$'}duplicate")

        assertTrue(turn.selectedSkillFragments.isEmpty())
    }

    @Test
    fun exactLinkedMentionDisambiguates() = runTest {
        val workspace = MemoryWorkspace(
            mapOf(
                "/workspace/.agents/skills/one/SKILL.md" to "---\nname: duplicate\ndescription: First\n---\nONE",
                "/workspace/.agents/skills/two/SKILL.md" to "---\nname: duplicate\ndescription: Second\n---\nTWO",
            ),
        )

        val turn = runtime(workspace).prepareTurn(
            "Use [${'$'}duplicate](/workspace/.agents/skills/two/SKILL.md)",
        )

        assertEquals(1, turn.selectedSkillFragments.size)
        assertContains(turn.selectedSkillFragments.single(), "TWO")
    }

    @Test
    fun resourceReadIsBoundToAuthorityPackageAndDirectory() = runTest {
        val workspace = MemoryWorkspace(
            mapOf(
                "/workspace/.agents/skills/safe/SKILL.md" to "---\nname: safe\ndescription: Safe reads\n---",
                "/workspace/.agents/skills/safe/references/info.md" to "inside",
                "/workspace/.agents/skills/outside.txt" to "outside",
            ),
        )
        val runtime = runtime(workspace)
        val skill = runtime.catalog().entries.single()
        val result = runtime.read(
            SkillReadRequest(skill.authority, skill.packageId, "/workspace/.agents/skills/safe/references/info.md"),
        )
        assertEquals("inside", result.contents)

        assertFailsWith<IllegalArgumentException> {
            runtime.read(
                SkillReadRequest(skill.authority, skill.packageId, "/workspace/.agents/skills/safe/../outside.txt"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            runtime.read(
                SkillReadRequest(skill.authority.copy(id = "wrong"), skill.packageId, skill.mainResource),
            )
        }
    }

    @Test
    fun hiddenDirectoriesAndInvalidSkillsDoNotBreakCatalog() = runTest {
        val workspace = MemoryWorkspace(
            mapOf(
                "/workspace/.agents/skills/.hidden/SKILL.md" to "---\nname: hidden\ndescription: Hidden\n---",
                "/workspace/.agents/skills/broken/SKILL.md" to "---\nname: broken\n---",
                "/workspace/.agents/skills/valid/SKILL.md" to "---\nname: valid\ndescription: Valid\n---",
            ),
        )

        val catalog = runtime(workspace).catalog()

        assertEquals(listOf("valid"), catalog.entries.map { it.name })
        assertEquals(1, catalog.warnings.size)
    }

    private fun runtime(workspace: AgentWorkspace): SkillRuntime = SkillRuntime(
        listOf(
            HostSkillProvider(
                workspace,
                listOf(HostSkillRoot("/workspace/.agents/skills", SkillScope.User)),
                "test",
            ),
        ),
    )

    private class MemoryWorkspace(
        private val files: Map<String, String>,
    ) : AgentWorkspace {
        override suspend fun readText(path: String): String = files[path] ?: error("Missing file: $path")

        override suspend fun writeText(path: String, content: String) = error("Not needed")

        override suspend fun list(path: String): List<AgentWorkspaceEntry> {
            val prefix = path.trimEnd('/') + "/"
            val children = linkedMapOf<String, AgentWorkspaceEntry>()
            files.forEach { (file, contents) ->
                if (!file.startsWith(prefix)) return@forEach
                val remainder = file.removePrefix(prefix)
                val name = remainder.substringBefore('/')
                val child = prefix + name
                val directory = '/' in remainder
                children[child] = AgentWorkspaceEntry(
                    path = child,
                    directory = directory,
                    size = if (directory) 0L else contents.encodeToByteArray().size.toLong(),
                )
            }
            if (children.isEmpty()) error("Missing directory: $path")
            return children.values.toList()
        }
    }

}
