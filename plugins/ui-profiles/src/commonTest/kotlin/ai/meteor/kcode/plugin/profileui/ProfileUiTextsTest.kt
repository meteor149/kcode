package ai.meteor.kcode.plugin.profileui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileUiTextsTest {
    @Test
    fun featureOwnedXmlDefaultsCoverBothLanguagesAndUnknownLanguagesUseEnglish() {
        val englishKeys = ProfileResourceStrings.keys.filterNot { it.endsWith("_zh") }
        assertTrue(englishKeys.isNotEmpty())
        for (key in englishKeys) {
            assertTrue(profileResourceText(key, "en").isNotBlank(), key)
            assertTrue(profileResourceText(key, "zh").isNotBlank(), key)
            assertEquals(profileResourceText(key, "en"), profileResourceText(key, "und"))
        }
        assertEquals("Profiles", profileResourceText("profile_title", "en"))
        assertEquals("Profile 配置方案", profileResourceText("profile_title", "zh"))
    }
}
