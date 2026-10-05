package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.plugin.api.FileSystemBackend
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.KcodeSkillWorkspace
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.nativefilesystem.DesktopNativeFileSystemPlugin
import ai.meteor.kcode.plugin.provider.PlatformFileSystemProviderPlugin
import ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class NativeWorkspaceCompositionTest {
    @Test
    fun actualJarWorkspaceWithdrawalRebindsPrivateSkillDiscovery(): Unit = runBlocking {
        val directory = Files.createTempDirectory("native-workspace-jar").toFile()
        val artifact = File(directory, "workspace.jar")
        // Package the actual private implementation graph, rather than borrow host helpers.
        val entries = mutableSetOf<String>()
        JarOutputStream(artifact.outputStream()).use { output ->
            listOf(DesktopNativeFileSystemPlugin::class.java, PlatformFileSystemProviderPlugin::class.java,
                WorkspaceSkillsPlugin::class.java).map { File(it.protectionDomain.codeSource.location.toURI()) }
                .distinct().forEach { source ->
                    ZipFile(source).use { zip ->
                        zip.entries().asSequence().filter { !it.isDirectory && entries.add(it.name) }.forEach { entry ->
                            output.putNextEntry(JarEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(output) }
                            output.closeEntry()
                        }
                    }
                }
        }
        check(artifact.setReadOnly())
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        val workspace = File(directory, "workspace").apply { mkdirs() }
        lateinit var fs: FileSystemBackend
        lateinit var scopedWorkspace: AgentWorkspace
        lateinit var skills: SkillRuntime
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(kcodePlugin(
                PluginDescriptor("test.capture", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-workspace", inject = dependencies(KcodeFileSystem.Key, KcodeSkillWorkspace.Key, KcodeSkills.Key)) { ctx, _ ->
                    fs = ctx.require(KcodeFileSystem.Key).backend
                    scopedWorkspace = ctx.require(KcodeSkillWorkspace.Key).workspace
                    skills = requireNotNull(ctx.require(KcodeSkills.Key).runtime)
                }, Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val skillsSpec = DynamicPluginSpec(
            id = "fixture.skills", version = "jar", entryClass = WorkspaceSkillsPlugin::class.java.name,
            artifactPath = artifact.path, sha256 = digest,
        )
        val fsSpec = DynamicPluginSpec(
            id = "fixture.fs", version = "jar", entryClass = DesktopNativeFileSystemPlugin::class.java.name,
            artifactPath = artifact.path, sha256 = digest, config = workspace.absolutePath,
        )
        try {
            runtime.pluginManager.install(skillsSpec)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == skillsSpec.id }.state)
            runtime.pluginManager.install(fsSpec)
            assertNotSame(DesktopNativeFileSystemPlugin::class.java.classLoader, fs.javaClass.classLoader)
            assertNotSame(WorkspaceSkillsPlugin::class.java.classLoader, skills.javaClass.classLoader)
            assertNotSame(fs.javaClass.classLoader, skills.javaClass.classLoader)
            scopedWorkspace.writeText("/workspace/user-data.txt", "persisted")
            assertEquals("persisted", fs.readBytes("/workspace/user-data.txt").decodeToString())
            scopedWorkspace.writeText(
                "/workspace/.agents/skills/example/SKILL.md",
                "---\nname: example\ndescription: Fixture skill\n---\nInstructions",
            )
            assertTrue(skills.catalog().entries.any { it.name == "example" })
            val previousSkills = skills
            runtime.pluginManager.setEnabled(skillsSpec.id, false)
            assertFailsWith<IllegalStateException> { previousSkills.catalog() }
            runtime.pluginManager.setEnabled(skillsSpec.id, true)
            assertNotSame(previousSkills, skills)
            assertTrue(skills.catalog().entries.any { it.name == "example" })

            val oldFs = fs
            val oldWorkspace = scopedWorkspace
            val oldSkills = skills
            runtime.pluginManager.setEnabled(fsSpec.id, false)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == skillsSpec.id }.state)
            assertFailsWith<IllegalStateException> { oldFs.normalize("/workspace") }
            assertFailsWith<IllegalStateException> { oldWorkspace.readText("/workspace/user-data.txt") }
            assertFailsWith<IllegalStateException> { oldSkills.catalog() }
            runtime.pluginManager.setEnabled(fsSpec.id, true)
            assertNotSame(oldWorkspace, scopedWorkspace)
            assertNotSame(oldSkills, skills)
            assertEquals("persisted", scopedWorkspace.readText("/workspace/user-data.txt"))
            assertTrue(skills.catalog().entries.any { it.name == "example" })
            runtime.pluginManager.uninstall(fsSpec.id)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == skillsSpec.id }.state)
        } finally {
            try { runtime.close() } finally { artifact.setWritable(true); directory.deleteRecursively() }
        }
    }
}
