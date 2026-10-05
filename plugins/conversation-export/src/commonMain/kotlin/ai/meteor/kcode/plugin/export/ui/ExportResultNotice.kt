package ai.meteor.kcode.plugin.export.ui

import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.localization.UiText

/** Export completion can arrive while the UI/localization contribution is being withdrawn. */
internal fun exportResultNotice(
    result: ImageSaveResult,
    truncated: Boolean,
    language: AppLanguage,
    localization: TranslationCatalog,
    unknownError: String,
): String? = when (result) {
    is ImageSaveResult.Saved -> localization.displayText(
        language,
        if (truncated) UiText.ExportSavedTruncated else UiText.ExportSaved,
        listOf(result.location),
    )
    ImageSaveResult.Shared -> localization.displayText(language, UiText.ShareOpened, emptyList())
    is ImageSaveResult.Failed -> localization.displayText(
        language, UiText.ExportFailed, listOf(result.reason ?: unknownError),
    )
    ImageSaveResult.Unsupported -> localization.displayText(language, UiText.ExportUnsupported, emptyList())
}
