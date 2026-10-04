package ai.meteor.kcode.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow

/** Opaque locale identifier; available choices and the default belong to a provider. */
data class AppLanguage(val code: String) {
    init { require(code.isNotBlank() && code == code.trim() && code.none(Char::isISOControl)) }
    companion object {
        val English = AppLanguage("en")
        val Chinese = AppLanguage("zh")
        fun fromCode(code: String) = AppLanguage(code)
    }
}

data class LocalizedText(val key: String) {
    init { require(key.isNotBlank()) }
}

data class LanguageOption(val language: AppLanguage, val displayNames: Map<String, String>)

data class LocalizationSnapshot(
    val languages: List<LanguageOption>,
    val defaultLanguage: AppLanguage,
    val fallbackLanguages: List<AppLanguage> = emptyList(),
) {
    fun selectLanguage(code: String): AppLanguage = languages.firstOrNull { it.language.code == code }?.language ?: defaultLanguage
}

interface TranslationCatalog {
    val available: StateFlow<Boolean>
    /** Null after withdrawal, for safe composition teardown without reviving a dictionary. */
    fun snapshot(): LocalizationSnapshot?
    /** Strict headless call. Disposed references and unknown keys are rejected. */
    fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any): String
    /** Rendering may finish while its owner is retiring; null means render no text. */
    fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String?
}

val LocalAppLanguage = compositionLocalOf { AppLanguage("und") }
val LocalTranslationCatalog = compositionLocalOf<TranslationCatalog?> { null }

@Composable
fun LocalizationContext(catalog: TranslationCatalog?, languageCode: String, content: @Composable () -> Unit) {
    if (catalog == null) return
    val available by catalog.available.collectAsState()
    if (!available) return
    val snapshot = catalog.snapshot() ?: return
    CompositionLocalProvider(
        LocalTranslationCatalog provides catalog,
        LocalAppLanguage provides snapshot.selectLanguage(languageCode),
        content = content,
    )
}

@Composable
fun text(value: LocalizedText, vararg formatArgs: Any): String {
    val catalog = LocalTranslationCatalog.current ?: return ""
    val available by catalog.available.collectAsState()
    return if (available) catalog.displayText(LocalAppLanguage.current, value, formatArgs.toList()).orEmpty() else ""
}
