package ai.meteor.kcode.skill

import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.AgentWorkspaceEntry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class BuiltinWebAppSkillTest {
    @Test
    fun materializesCatalogsAndExplicitlyLoadsWebBuilderSkill() = runTest {
        val workspace = WritableMemoryWorkspace()
        val runtime = createWorkspaceSkillRuntime(workspace, "test")

        val catalog = runtime.catalog(forceReload = true)
        val skill = catalog.entries.single { it.name == "kcode-web-app-builder" }
        assertEquals(SkillScope.System, skill.scope)
        assertEquals(BuiltinWebAppSkillPath, skill.mainResource)

        val selected = runtime.prepareTurn("Use ${'$'}kcode-web-app-builder to make a timer")
        val prompt = selected.selectedSkillFragments.single()
        assertContains(prompt, "ask the user whether to save it as an artifact")
        assertContains(prompt, "save_web_artifact")
        assertContains(prompt, "screenshot_web_container")
    }

    private class WritableMemoryWorkspace : AgentWorkspace {
        private val files = mutableMapOf<String, String>()

        override suspend fun readText(path: String): String = files[path] ?: error("Missing file: $path")

        override suspend fun writeText(path: String, content: String) {
            files[path] = content
        }

        override suspend fun list(path: String): List<AgentWorkspaceEntry> {
            val prefix = path.trimEnd('/') + "/"
            val entries = linkedMapOf<String, AgentWorkspaceEntry>()
            files.forEach { (file, contents) ->
                if (!file.startsWith(prefix)) return@forEach
                val remainder = file.removePrefix(prefix)
                val name = remainder.substringBefore('/')
                val child = prefix + name
                val directory = '/' in remainder
                entries[child] = AgentWorkspaceEntry(
                    path = child,
                    directory = directory,
                    size = if (directory) 0L else contents.encodeToByteArray().size.toLong(),
                )
            }
            if (entries.isEmpty()) error("Missing directory: $path")
            return entries.values.toList()
        }
    }
}
