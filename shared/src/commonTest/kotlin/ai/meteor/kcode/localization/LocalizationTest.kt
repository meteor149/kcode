package ai.meteor.kcode.localization

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalizationTest {
    @Test
    fun persistedLanguageCodesAreStable() {
        assertEquals(AppLanguage.Chinese, AppLanguage.fromCode("zh"))
        assertEquals(AppLanguage.English, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromCode("unsupported"))
    }
}
