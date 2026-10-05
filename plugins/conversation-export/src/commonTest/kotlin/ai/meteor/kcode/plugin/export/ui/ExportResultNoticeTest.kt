package ai.meteor.kcode.plugin.export.ui

import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.localization.TranslationCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

class ExportResultNoticeTest {
    @Test
    fun successfulOrFailedExportFinishingAfterDictionaryWithdrawalHasNoLateNotice() = runTest {
        for (outcome in listOf(ImageSaveResult.Saved("fixture.png"), ImageSaveResult.Failed("IO failure"))) {
            val catalog = object : TranslationCatalog {
                override val available = MutableStateFlow(true)
                override fun snapshot(): LocalizationSnapshot? = null
                override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any): String =
                    error("Strict headless translation must not run for a retiring UI")
                override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String? =
                    if (available.value) "visible" else null
            }
            val exportFinished = CompletableDeferred<ImageSaveResult>()
            val entered = CompletableDeferred<Unit>()
            val notice = async {
                entered.complete(Unit)
                exportResultNotice(exportFinished.await(), false, AppLanguage.English, catalog, "unknown")
            }
            entered.await()
            catalog.available.value = false
            exportFinished.complete(outcome)
            assertNull(notice.await())
        }
    }
}
