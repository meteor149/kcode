package ai.meteor.kcode.plugin.agentloop

import ai.meteor.kcode.skill.SkillTurnContext
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectedSkillContextTest {
    @Test
    fun emptySelectionDoesNotInjectCatalogOrWarningsIntoTheUserTranscript() {
        assertEquals("conversation", appendSelectedSkillFragments(
            SkillTurnContext("discovery metadata", emptyList(), emptyList()), "conversation",
        ))
        assertEquals("selected first\n\nselected second\n\nconversation", appendSelectedSkillFragments(
            SkillTurnContext("discovery metadata", listOf("selected first", "selected second"), emptyList()), "conversation",
        ))
    }
}
