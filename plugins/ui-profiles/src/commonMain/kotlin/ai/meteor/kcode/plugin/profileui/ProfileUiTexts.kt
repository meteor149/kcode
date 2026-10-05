package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.plugin.profileui.resources.Res
import ai.meteor.kcode.plugin.profileui.resources.allStringResources
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Follow the app language and custom catalog; fallback text is owned by this private package. */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun profileText(resource: StringResource): String {
    val language = LocalAppLanguage.current
    val translated = LocalTranslationCatalog.current?.displayText(language, LocalizedText(resource.key), emptyList())
    return translated ?: stringResource(if (language.code == "zh")
        Res.allStringResources.getValue("${resource.key}_zh") else resource)
}
