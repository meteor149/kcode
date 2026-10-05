package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.AgentWorkspace
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.FileSystemBackend
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.KcodeSkillWorkspace
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.nativefilesystem.AndroidNativeFileSystemPlugin
import ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidNativeWorkspaceTest {
    @Test(timeout = 60_000)
    fun actualApkWorkspaceOwnsSkillStateAndRevokesBothCapabilities(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-workspace-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "workspace.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        val digest = packageFileSha256(apk)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): Context = isolated }
        }
        lateinit var fs: FileSystemBackend
        lateinit var workspace: AgentWorkspace
        lateinit var skills: SkillRuntime
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            hostInputs = AndroidPluginHostInputs(activity),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(kcodePlugin(
                PluginDescriptor("test.capture", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-workspace", inject = dependencies(KcodeFileSystem.Key, KcodeSkillWorkspace.Key, KcodeSkills.Key)) { ctx, _ ->
                    fs = ctx.require(KcodeFileSystem.Key).backend
                    workspace = ctx.require(KcodeSkillWorkspace.Key).workspace
                    skills = requireNotNull(ctx.require(KcodeSkills.Key).runtime)
                }, Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val skillsSpec = DynamicPluginSpec(
            id = "fixture.skills", version = "apk", entryClass = WorkspaceSkillsPlugin::class.java.name,
            artifactPath = apk.path, sha256 = digest, packageName = instrumentation.context.packageName,
        )
        val fsSpec = skillsSpec.copy(id = "fixture.fs", entryClass = AndroidNativeFileSystemPlugin::class.java.name)
        try {
            runtime.pluginManager.install(skillsSpec)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == skillsSpec.id }.state)
            runtime.pluginManager.install(fsSpec)
            assertNotSame(AndroidNativeFileSystemPlugin::class.java.classLoader, fs.javaClass.classLoader)
            assertNotSame(WorkspaceSkillsPlugin::class.java.classLoader, skills.javaClass.classLoader)
            workspace.writeText("/workspace/persisted.txt", "owned-apk")
            assertEquals("owned-apk", fs.readBytes("/workspace/persisted.txt").decodeToString())
            skills.catalog()
            val builtin = File(directory, "agent_workspace/.kcode/skills/web-app-builder/SKILL.md")
            assertTrue(builtin.isFile)
            val firstSkills = skills
            runtime.pluginManager.setEnabled(skillsSpec.id, false)
            assertFailsWith<IllegalStateException> { firstSkills.catalog() }
            assertTrue(builtin.delete())
            runtime.pluginManager.setEnabled(skillsSpec.id, true)
            assertNotSame(firstSkills, skills)
            skills.catalog()
            assertTrue(builtin.isFile)
            val previousFs = fs
            val previousWorkspace = workspace
            val previousSkills = skills
            runtime.pluginManager.setEnabled(fsSpec.id, false)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == skillsSpec.id }.state)
            assertFailsWith<IllegalStateException> { previousFs.normalize("/workspace") }
            assertFailsWith<IllegalStateException> { previousWorkspace.readText("/workspace/persisted.txt") }
            assertFailsWith<IllegalStateException> { previousSkills.catalog() }
            runtime.pluginManager.setEnabled(fsSpec.id, true)
            assertNotSame(previousWorkspace, workspace)
            assertEquals("owned-apk", workspace.readText("/workspace/persisted.txt"))
            skills.catalog()
            runtime.pluginManager.uninstall(fsSpec.id)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == skillsSpec.id }.state)
        } finally {
            try { runtime.close() } finally { apk.setWritable(true); directory.deleteRecursively() }
        }
    }
}
