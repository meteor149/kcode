package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.localization.LocalizedText
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource

/** Follow the app language and custom catalog; fallback text is owned by this private package. */
@Composable
internal fun profileText(resource: StringResource): String {
    val language = LocalAppLanguage.current
    val translated = LocalTranslationCatalog.current?.displayText(language, LocalizedText(resource.key), emptyList())
    return translated ?: profileResourceText(resource.key, language.code)
}

/** XML remains the source of truth; generated defaults travel with the private implementation. */
internal fun profileResourceText(key: String, language: String): String =
    ProfileResourceStrings.getValue(if (language.startsWith("zh")) "${key}_zh" else key)
