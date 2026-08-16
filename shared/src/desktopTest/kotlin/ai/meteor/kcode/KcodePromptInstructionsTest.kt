package ai.meteor.kcode

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class KcodePromptInstructionsTest {
    @Test
    fun baseInstructionsStayGeneralAndContainNoWebWorkflow() {
        assertContains(KcodeBaseInstructions, "You are kcode")
        assertContains(KcodeBaseInstructions, "When using tools")
        assertFalse(KcodeBaseInstructions.contains("Web app"))
        assertFalse(KcodeBaseInstructions.contains("preview_web_app"))
        assertFalse(KcodeBaseInstructions.contains("inspect_web_container"))
        assertFalse(KcodeBaseInstructions.contains("HTTP service"))
        assertFalse(CjkCharacter.containsMatchIn(KcodeBaseInstructions))
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
        assertContains(prompt, "up to 5 agents can be active at once")
        assertContains(prompt, "Proactive multi-agent delegation is active")
        assertContains(prompt, "<multi_agent_mode>")
    }

    private companion object {
        val CjkCharacter = Regex("[\\u3400-\\u9FFF]")
    }
}
