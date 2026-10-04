package ai.meteor.kcode.plugin.overlay

import kotlin.test.Test

class ConversationOverlayPresentationTest {
    @Test
    fun usesCurrentThemeAndMessageContributionsWithoutFallback() =
        ConversationOverlayPresentationScenario().verifyCurrentContributions()
}
