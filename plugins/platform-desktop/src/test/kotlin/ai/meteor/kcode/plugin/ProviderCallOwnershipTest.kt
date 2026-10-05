package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.skill.SkillAuthority
import ai.meteor.kcode.skill.SkillAuthorityKind
import ai.meteor.kcode.skill.SkillCatalog
import ai.meteor.kcode.skill.SkillReadRequest
import ai.meteor.kcode.skill.SkillReadResult
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.skill.SkillTurnContext
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class ProviderCallOwnershipTest {
    @Test
    fun interactionWithdrawalJoinsBothCallbacksAndRejectsOldPolicies() = runTest {
        for (readMode in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var slow = true
            suspend fun call() {
                if (!slow) return
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
            val policy = InteractionPolicy(
                permissionModeProvider = { call(); ToolPermissionMode.Bypass },
                approver = ToolCallApprover { call(); true },
            )
            lateinit var current: InteractionPolicy
            val runtime = KcodePluginRuntime.create(configuration(listOf(
                kcodePlugin(descriptor("test.capture"), plugin<Unit>(
                    name = "capture-interaction", inject = dependencies(KcodeInteraction.Key),
                ) { ctx, _ -> current = ctx.require(KcodeInteraction.Key).policy }, Unit),
            )).copy(interactionPolicy = policy, profile = KcodePluginProfile()))
            val old = current
            val request = ToolApprovalRequest("test", "input", "purpose")
            try {
                val operation = backgroundScope.async {
                    if (readMode) old.permissionModeProvider() else old.approver.approve(request)
                }
                entered.await()
                val disabling = async { runtime.pluginManager.setEnabled("provider.interaction.platform", false) }
                cleaning.await()
                assertFalse(disabling.isCompleted)
                assertFailsWith<IllegalStateException> { old.permissionModeProvider() }
                assertFailsWith<IllegalStateException> { old.approver.approve(request) }
                release.complete(Unit)
                disabling.await()
                assertTrue(operation.isCompleted)
                slow = false
                runtime.pluginManager.setEnabled("provider.interaction.platform", true)
                assertFalse(old === current)
                assertEquals(ToolPermissionMode.Bypass, current.permissionModeProvider())
                assertTrue(current.approver.approve(request))
            } finally { release.complete(Unit); runtime.close() }
        }
    }

    @Test
    fun skillWithdrawalJoinsEveryExecutionEntryAndRebindsToANewFacade() = runTest {
        for (entry in 0..2) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var slow = true
            suspend fun call() {
                if (!slow) return
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
            val delegate = object : SkillRuntime {
                override suspend fun catalog(forceReload: Boolean): SkillCatalog {
                    call(); return SkillCatalog(emptyList(), generation = "test")
                }
                override suspend fun prepareTurn(originalUserPrompt: String): SkillTurnContext {
                    call(); return SkillTurnContext(originalUserPrompt, emptyList(), emptyList())
                }
                override suspend fun read(request: SkillReadRequest): SkillReadResult {
                    call(); return SkillReadResult(request.authority, request.packageId, request.resourceId, "contents", false)
                }
            }
            lateinit var current: SkillRuntime
            val runtime = KcodePluginRuntime.create(configuration(listOf(
                kcodePlugin(descriptor("test.skills"), SkillServicePlugin, SkillServicePluginConfig(delegate)),
                kcodePlugin(descriptor("test.capture"), plugin<Unit>(
                    name = "capture-skills", inject = dependencies(KcodeSkills.Key),
                ) { ctx, _ -> current = checkNotNull(ctx.require(KcodeSkills.Key).runtime) }, Unit),
            )))
            val old = current
            val request = SkillReadRequest(SkillAuthority(SkillAuthorityKind.Host, "test"), "package", "resource")
            try {
                val operation = backgroundScope.async {
                    when (entry) { 0 -> old.catalog(); 1 -> old.prepareTurn("prompt"); else -> old.read(request) }
                }
                entered.await()
                val disabling = async { runtime.pluginManager.setEnabled("test.skills", false) }
                cleaning.await()
                assertFalse(disabling.isCompleted)
                assertFailsWith<IllegalStateException> { old.catalog() }
                assertFailsWith<IllegalStateException> { old.prepareTurn("late") }
                assertFailsWith<IllegalStateException> { old.read(request) }
                release.complete(Unit)
                disabling.await()
                assertTrue(operation.isCompleted)
                slow = false
                runtime.pluginManager.setEnabled("test.skills", true)
                assertFalse(old === current)
                assertEquals("test", current.catalog().generation)
                assertEquals("new", current.prepareTurn("new").catalogInstructions)
                assertEquals("contents", current.read(request).contents)
            } finally { release.complete(Unit); runtime.close() }
        }
    }

    @Test
    fun agentProviderAndItsDependencyWithdrawalInvalidateCapturedChatServices() = runTest {
        lateinit var current: ai.meteor.kcode.chat.ChatService
        val capture = kcodePlugin(descriptor("test.capture-agent"), plugin<Unit>(
            name = "capture-agent", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeAgents.Key),
        ) { ctx, _ -> current = ctx.require(ai.meteor.kcode.plugin.api.KcodeAgents.Key).chatService }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            featurePlugins = listOf(capture),
        ))
        val model = ai.meteor.kcode.model.ModelConfiguration(ai.meteor.kcode.model.ModelProvider.Ollama, "fixture", "", temperature = 0.6)
        try {
            val withoutSkills = current
            runtime.pluginManager.setEnabled("provider.skills.platform", false)
            assertTrue(withoutSkills === current)
            runtime.pluginManager.setEnabled("provider.skills.platform", true)
            assertTrue(withoutSkills === current)
            for (id in listOf("provider.agent-loop.koog", "provider.interaction.platform")) {
                val old = current
                runtime.pluginManager.setEnabled(id, false)
                val replyError = assertFailsWith<IllegalStateException> { old.reply(model, emptyList(), "late") }
                assertTrue(replyError.message.orEmpty().contains("disposed"))
                val streamError = assertFailsWith<IllegalStateException> {
                    old.replyStreaming(model, emptyList(), "late", onDelta = {})
                }
                assertTrue(streamError.message.orEmpty().contains("disposed"))
                runtime.pluginManager.setEnabled(id, true)
                assertFalse(old === current)
            }
        } finally { runtime.close() }
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private fun configuration(plugins: List<KcodePluginMount>) = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
        profile = KcodePluginProfile(includeDefaults = false),
        featurePlugins = plugins,
    )
}
