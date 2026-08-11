package ai.meteor.kcode

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class KcodePromptInstructionsTest {
    @Test
    fun baseInstructionsStayGeneralAndContainNoWebWorkflow() {
        assertContains(KcodeBaseInstructions, "你是 kcode")
        assertContains(KcodeBaseInstructions, "使用工具时遵守")
        assertFalse(KcodeBaseInstructions.contains("Web 应用"))
        assertFalse(KcodeBaseInstructions.contains("preview_web_app"))
        assertFalse(KcodeBaseInstructions.contains("inspect_web_container"))
        assertFalse(KcodeBaseInstructions.contains("HTTP 服务"))
    }

    @Test
    fun dynamicSkillCatalogIsComposedSeparatelyFromBaseInstructions() {
        val catalog = "## Skills\n- demo: Example (file: /workspace/demo/SKILL.md)"

        val composed = buildKcodeSystemPrompt(catalog)

        assertFalse(KcodeBaseInstructions.contains(catalog))
        assertContains(composed, KcodeBaseInstructions)
        assertContains(composed, catalog)
    }

    @Test
    fun multiAgentPromptUsesCodexV2TaskPathsAndEnablesProactiveDelegation() {
        val prompt = buildKcodeSystemPrompt(null, RootMultiAgentInstructions)

        assertContains(prompt, "You are `/root`, the primary agent")
        assertContains(prompt, "up to 4 agents can be active at once")
        assertContains(prompt, "Proactive multi-agent delegation is active")
        assertContains(prompt, "<multi_agent_mode>")
    }
}
