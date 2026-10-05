package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LanguageSettingsPolicy
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ApplicationLanguageProjectionTest {
    @Test
    fun rootBorrowsOptionalLanguagePolicyAndRevokesOnlyItsOwnProjection() {
        val policy = object : LanguageSettingsPolicy {
            override fun preferredLanguage(settings: StoredAppSettings) = AppLanguage.English
            override fun update(settings: StoredAppSettings, language: AppLanguage) = settings
        }
        val primary = object : TranslationCatalog {
            override val available = MutableStateFlow(true)
            override val languageSettings = policy
            override fun snapshot() = LocalizationSnapshot(emptyList(), AppLanguage.English)
            override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any) = ""
            override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String? = null
        }
        val root = ApplicationTextCatalog(primary, emptyList())
        assertSame(policy, root.languageSettings)
        root.close()
        assertNull(root.languageSettings)
        assertSame(policy, primary.languageSettings)
        assertEquals(AppLanguage.English, policy.preferredLanguage(LegacySettings(language = "zh")))
        val fallback = ApplicationTextCatalog(null, emptyList())
        assertNull(fallback.languageSettings)
        assertEquals(AppLanguage.English, fallback.snapshot()?.defaultLanguage)
        fallback.close()
    }
}
