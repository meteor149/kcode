package ai.meteor.kcode.plugin.prompt

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

    private companion object {
        val CjkCharacter = Regex("[\\u3400-\\u9FFF]")
    }
}
