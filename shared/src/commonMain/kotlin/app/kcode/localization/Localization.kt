package app.kcode.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import app.kcode.model.ModelOption
import app.kcode.model.ModelProvider
import app.kcode.settings.SettingsProtection
import app.kcode.chat.ChatAvailability
import kcode.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

enum class AppLanguage(val code: String) {
    English("en"),
    Chinese("zh");

    companion object {
        fun fromCode(code: String): AppLanguage = entries.firstOrNull { it.code == code } ?: Chinese
    }
}

val LocalAppLanguage = compositionLocalOf { AppLanguage.Chinese }

data class LocalizedText(val english: StringResource, val chinese: StringResource)

@Composable
fun text(value: LocalizedText, vararg formatArgs: Any): String =
    stringResource(
        if (LocalAppLanguage.current == AppLanguage.Chinese) value.chinese else value.english,
        *formatArgs,
    )

suspend fun resolveText(language: AppLanguage, value: LocalizedText, vararg formatArgs: Any): String =
    getString(if (language == AppLanguage.Chinese) value.chinese else value.english, *formatArgs)

object UiText {
    val ReadSettingsFailed = LocalizedText(Res.string.read_settings_failed_en, Res.string.read_settings_failed_zh)
    val SaveSettingsFailed = LocalizedText(Res.string.save_settings_failed_en, Res.string.save_settings_failed_zh)
    val UnknownError = LocalizedText(Res.string.unknown_error_en, Res.string.unknown_error_zh)
    val CloseSidebar = LocalizedText(Res.string.close_sidebar_en, Res.string.close_sidebar_zh)
    val NewChat = LocalizedText(Res.string.new_chat_en, Res.string.new_chat_zh)
    val NewChatShortcut = LocalizedText(Res.string.new_chat_shortcut_en, Res.string.new_chat_shortcut_zh)
    val Recent = LocalizedText(Res.string.recent_en, Res.string.recent_zh)
    val EmptyConversations = LocalizedText(Res.string.empty_conversations_en, Res.string.empty_conversations_zh)
    val PinConversation = LocalizedText(Res.string.pin_conversation_en, Res.string.pin_conversation_zh)
    val DeleteConversation = LocalizedText(Res.string.delete_conversation_en, Res.string.delete_conversation_zh)
    val OpenSettings = LocalizedText(Res.string.open_settings_en, Res.string.open_settings_zh)
    val SelectModel = LocalizedText(Res.string.select_model_en, Res.string.select_model_zh)
    val OpenSidebar = LocalizedText(Res.string.open_sidebar_en, Res.string.open_sidebar_zh)
    val Settings = LocalizedText(Res.string.settings_en, Res.string.settings_zh)
    val BackToChat = LocalizedText(Res.string.back_to_chat_en, Res.string.back_to_chat_zh)
    val SettingsIntro = LocalizedText(Res.string.settings_intro_en, Res.string.settings_intro_zh)
    val General = LocalizedText(Res.string.general_en, Res.string.general_zh)
    val GeneralDescription = LocalizedText(Res.string.general_desc_en, Res.string.general_desc_zh)
    val Language = LocalizedText(Res.string.language_en, Res.string.language_zh)
    val LanguageDescription = LocalizedText(Res.string.language_desc_en, Res.string.language_desc_zh)
    val English = LocalizedText(Res.string.english_en, Res.string.english_zh)
    val SimplifiedChinese = LocalizedText(Res.string.simplified_chinese_en, Res.string.simplified_chinese_zh)
    val ModelProvider = LocalizedText(Res.string.model_provider_en, Res.string.model_provider_zh)
    val ModelProviderDescription = LocalizedText(Res.string.model_provider_desc_en, Res.string.model_provider_desc_zh)
    val InternetSearch = LocalizedText(Res.string.internet_search_en, Res.string.internet_search_zh)
    val InternetSearchDescription = LocalizedText(Res.string.internet_search_desc_en, Res.string.internet_search_desc_zh)
    val SearchProvider = LocalizedText(Res.string.search_provider_en, Res.string.search_provider_zh)
    val BrightDataDescription = LocalizedText(Res.string.bright_data_desc_en, Res.string.bright_data_desc_zh)
    val ExaSearchDescription = LocalizedText(Res.string.exa_search_desc_en, Res.string.exa_search_desc_zh)
    val GoogleSearchDescription = LocalizedText(Res.string.google_search_desc_en, Res.string.google_search_desc_zh)
    val GoogleNoKeyNotice = LocalizedText(Res.string.google_no_key_notice_en, Res.string.google_no_key_notice_zh)
    val ApiKeyRequired = LocalizedText(Res.string.api_key_required_en, Res.string.api_key_required_zh)
    val NoApiKeyRequired = LocalizedText(Res.string.no_api_key_required_en, Res.string.no_api_key_required_zh)
    val Configured = LocalizedText(Res.string.configured_en, Res.string.configured_zh)
    val NotConfigured = LocalizedText(Res.string.not_configured_en, Res.string.not_configured_zh)
    val ModelService = LocalizedText(Res.string.model_service_en, Res.string.model_service_zh)
    val ShellExecution = LocalizedText(Res.string.shell_execution_en, Res.string.shell_execution_zh)
    val ShellExecutionDescription = LocalizedText(Res.string.shell_execution_desc_en, Res.string.shell_execution_desc_zh)
    val ShellExecutionWarning = LocalizedText(Res.string.shell_execution_warning_en, Res.string.shell_execution_warning_zh)
    val ShellModeApp = LocalizedText(Res.string.shell_mode_app_en, Res.string.shell_mode_app_zh)
    val ShellModeAppDescription = LocalizedText(Res.string.shell_mode_app_desc_en, Res.string.shell_mode_app_desc_zh)
    val ShellModeAdb = LocalizedText(Res.string.shell_mode_adb_en, Res.string.shell_mode_adb_zh)
    val ShellModeAdbDescription = LocalizedText(Res.string.shell_mode_adb_desc_en, Res.string.shell_mode_adb_desc_zh)
    val ShellModeRoot = LocalizedText(Res.string.shell_mode_root_en, Res.string.shell_mode_root_zh)
    val ShellModeRootDescription = LocalizedText(Res.string.shell_mode_root_desc_en, Res.string.shell_mode_root_desc_zh)
    val ToolPermissionMode = LocalizedText(Res.string.tool_permission_mode_en, Res.string.tool_permission_mode_zh)
    val ToolPermissionShort = LocalizedText(Res.string.tool_permission_short_en, Res.string.tool_permission_short_zh)
    val ToolPermissionDenyDescription = LocalizedText(Res.string.tool_permission_deny_desc_en, Res.string.tool_permission_deny_desc_zh)
    val ToolPermissionAskDescription = LocalizedText(Res.string.tool_permission_ask_desc_en, Res.string.tool_permission_ask_desc_zh)
    val ToolPermissionBypassDescription = LocalizedText(Res.string.tool_permission_bypass_desc_en, Res.string.tool_permission_bypass_desc_zh)
    val Provider = LocalizedText(Res.string.provider_en, Res.string.provider_zh)
    val ApiKey = LocalizedText(Res.string.api_key_en, Res.string.api_key_zh)
    val Endpoint = LocalizedText(Res.string.endpoint_en, Res.string.endpoint_zh)
    val Region = LocalizedText(Res.string.region_en, Res.string.region_zh)
    val Deployment = LocalizedText(Res.string.deployment_en, Res.string.deployment_zh)
    val DeploymentHint = LocalizedText(Res.string.deployment_hint_en, Res.string.deployment_hint_zh)
    val ApiVersion = LocalizedText(Res.string.api_version_en, Res.string.api_version_zh)
    val OpenAiKeyHint = LocalizedText(Res.string.openai_key_hint_en, Res.string.openai_key_hint_zh)
    val DeepSeekKeyHint = LocalizedText(Res.string.deepseek_key_hint_en, Res.string.deepseek_key_hint_zh)
    val GlmKeyHint = LocalizedText(Res.string.glm_key_hint_en, Res.string.glm_key_hint_zh)
    val SaveRequestHint = LocalizedText(Res.string.save_request_hint_en, Res.string.save_request_hint_zh)
    val SaveSettings = LocalizedText(Res.string.save_settings_en, Res.string.save_settings_zh)
    val ChooseModel = LocalizedText(Res.string.choose_model_en, Res.string.choose_model_zh)
    val ModelLabel = LocalizedText(Res.string.model_label_en, Res.string.model_label_zh)
    val Creativity = LocalizedText(Res.string.creativity_en, Res.string.creativity_zh)
    val CreativityDescription = LocalizedText(Res.string.creativity_desc_en, Res.string.creativity_desc_zh)
    val CreativityUltraHigh = LocalizedText(Res.string.creativity_ultra_high_en, Res.string.creativity_ultra_high_zh)
    val CreativityHighest = LocalizedText(Res.string.creativity_highest_en, Res.string.creativity_highest_zh)
    val CreativityVeryHigh = LocalizedText(Res.string.creativity_very_high_en, Res.string.creativity_very_high_zh)
    val CreativityHigh = LocalizedText(Res.string.creativity_high_en, Res.string.creativity_high_zh)
    val CreativityMedium = LocalizedText(Res.string.creativity_medium_en, Res.string.creativity_medium_zh)
    val CreativityLight = LocalizedText(Res.string.creativity_light_en, Res.string.creativity_light_zh)
    val PermissionLevel = LocalizedText(Res.string.permission_level_en, Res.string.permission_level_zh)
    val ExportAction = LocalizedText(Res.string.export_action_en, Res.string.export_action_zh)
    val Cancel = LocalizedText(Res.string.cancel_en, Res.string.cancel_zh)
    val CopyText = LocalizedText(Res.string.copy_text_en, Res.string.copy_text_zh)
    val ApplyConfiguration = LocalizedText(Res.string.apply_configuration_en, Res.string.apply_configuration_zh)
    val EnterApiKey = LocalizedText(Res.string.enter_api_key_en, Res.string.enter_api_key_zh)
    val Hide = LocalizedText(Res.string.hide_en, Res.string.hide_zh)
    val Show = LocalizedText(Res.string.show_en, Res.string.show_zh)
    val ModelConnectionFailed = LocalizedText(Res.string.model_connection_failed_en, Res.string.model_connection_failed_zh)
    val SetupModelFirst = LocalizedText(Res.string.setup_model_first_en, Res.string.setup_model_first_zh)
    val WelcomeTitle = LocalizedText(Res.string.welcome_title_en, Res.string.welcome_title_zh)
    val WelcomeBody = LocalizedText(Res.string.welcome_body_en, Res.string.welcome_body_zh)
    val SuggestionIdea = LocalizedText(Res.string.suggestion_idea_en, Res.string.suggestion_idea_zh)
    val SuggestionCode = LocalizedText(Res.string.suggestion_code_en, Res.string.suggestion_code_zh)
    val SuggestionPlan = LocalizedText(Res.string.suggestion_plan_en, Res.string.suggestion_plan_zh)
    val You = LocalizedText(Res.string.you_en, Res.string.you_zh)
    val Thinking = LocalizedText(Res.string.thinking_en, Res.string.thinking_zh)
    val ToolRunning = LocalizedText(Res.string.tool_running_en, Res.string.tool_running_zh)
    val ToolSucceeded = LocalizedText(Res.string.tool_succeeded_en, Res.string.tool_succeeded_zh)
    val ToolFailed = LocalizedText(Res.string.tool_failed_en, Res.string.tool_failed_zh)
    val ToolInput = LocalizedText(Res.string.tool_input_en, Res.string.tool_input_zh)
    val ToolOutput = LocalizedText(Res.string.tool_output_en, Res.string.tool_output_zh)
    val MessagePlaceholder = LocalizedText(Res.string.message_placeholder_en, Res.string.message_placeholder_zh)
    val ReplyPlaceholder = LocalizedText(Res.string.reply_placeholder_en, Res.string.reply_placeholder_zh)
    val RegenerateAnswer = LocalizedText(Res.string.regenerate_answer_en, Res.string.regenerate_answer_zh)
    val ExportConversation = LocalizedText(Res.string.export_conversation_en, Res.string.export_conversation_zh)
    val ExportingConversation = LocalizedText(Res.string.exporting_conversation_en, Res.string.exporting_conversation_zh)
    val ExportSaved = LocalizedText(Res.string.export_saved_en, Res.string.export_saved_zh)
    val ExportSavedTruncated = LocalizedText(Res.string.export_saved_truncated_en, Res.string.export_saved_truncated_zh)
    val ExportFailed = LocalizedText(Res.string.export_failed_en, Res.string.export_failed_zh)
    val ExportUnsupported = LocalizedText(Res.string.export_unsupported_en, Res.string.export_unsupported_zh)
    val ExportTruncatedFooter = LocalizedText(Res.string.export_truncated_footer_en, Res.string.export_truncated_footer_zh)
    val Chats = LocalizedText(Res.string.chats_en, Res.string.chats_zh)
    val Projects = LocalizedText(Res.string.projects_en, Res.string.projects_zh)
    val Artifacts = LocalizedText(Res.string.artifacts_en, Res.string.artifacts_zh)
    val Code = LocalizedText(Res.string.code_en, Res.string.code_zh)
    val SaveToPhotos = LocalizedText(Res.string.save_to_photos_en, Res.string.save_to_photos_zh)
    val ShareImage = LocalizedText(Res.string.share_image_en, Res.string.share_image_zh)
    val SelectedMessages = LocalizedText(Res.string.selected_messages_en, Res.string.selected_messages_zh)
    val ShareOpened = LocalizedText(Res.string.share_opened_en, Res.string.share_opened_zh)
    val SendShortcut = LocalizedText(Res.string.send_shortcut_en, Res.string.send_shortcut_zh)
    val BrowserGatewayStatus = LocalizedText(Res.string.browser_gateway_status_en, Res.string.browser_gateway_status_zh)
    val BrowserGatewayError = LocalizedText(Res.string.browser_gateway_error_en, Res.string.browser_gateway_error_zh)
    val IosGatewayStatus = LocalizedText(Res.string.ios_gateway_status_en, Res.string.ios_gateway_status_zh)
    val IosGatewayError = LocalizedText(Res.string.ios_gateway_error_en, Res.string.ios_gateway_error_zh)
}

@Composable
fun availabilityStatus(value: ChatAvailability): String = text(
    if (value == ChatAvailability.BrowserGateway) UiText.BrowserGatewayStatus else UiText.IosGatewayStatus,
)

@Composable
fun availabilityError(value: ChatAvailability): String = text(
    if (value == ChatAvailability.BrowserGateway) UiText.BrowserGatewayError else UiText.IosGatewayError,
)

@Composable
fun providerName(provider: ModelProvider): String = text(
    when (provider) {
        ModelProvider.OpenAI -> LocalizedText(Res.string.provider_openai_en, Res.string.provider_openai_zh)
        ModelProvider.AzureOpenAI -> LocalizedText(Res.string.provider_azure_openai_en, Res.string.provider_azure_openai_zh)
        ModelProvider.Anthropic -> LocalizedText(Res.string.provider_anthropic_en, Res.string.provider_anthropic_zh)
        ModelProvider.Google -> LocalizedText(Res.string.provider_google_en, Res.string.provider_google_zh)
        ModelProvider.DeepSeek -> LocalizedText(Res.string.provider_deepseek_en, Res.string.provider_deepseek_zh)
        ModelProvider.OpenRouter -> LocalizedText(Res.string.provider_openrouter_en, Res.string.provider_openrouter_zh)
        ModelProvider.Bedrock -> LocalizedText(Res.string.provider_bedrock_en, Res.string.provider_bedrock_zh)
        ModelProvider.Mistral -> LocalizedText(Res.string.provider_mistral_en, Res.string.provider_mistral_zh)
        ModelProvider.Alibaba -> LocalizedText(Res.string.provider_alibaba_en, Res.string.provider_alibaba_zh)
        ModelProvider.Ollama -> LocalizedText(Res.string.provider_ollama_en, Res.string.provider_ollama_zh)
        ModelProvider.GLM -> LocalizedText(Res.string.provider_glm_en, Res.string.provider_glm_zh)
    },
)

@Composable
fun providerNote(provider: ModelProvider): String = text(
    when (provider) {
        ModelProvider.OpenAI -> LocalizedText(Res.string.provider_openai_note_en, Res.string.provider_openai_note_zh)
        ModelProvider.AzureOpenAI -> LocalizedText(Res.string.provider_azure_openai_note_en, Res.string.provider_azure_openai_note_zh)
        ModelProvider.Anthropic -> LocalizedText(Res.string.provider_anthropic_note_en, Res.string.provider_anthropic_note_zh)
        ModelProvider.Google -> LocalizedText(Res.string.provider_google_note_en, Res.string.provider_google_note_zh)
        ModelProvider.DeepSeek -> LocalizedText(Res.string.provider_deepseek_note_en, Res.string.provider_deepseek_note_zh)
        ModelProvider.OpenRouter -> LocalizedText(Res.string.provider_openrouter_note_en, Res.string.provider_openrouter_note_zh)
        ModelProvider.Bedrock -> LocalizedText(Res.string.provider_bedrock_note_en, Res.string.provider_bedrock_note_zh)
        ModelProvider.Mistral -> LocalizedText(Res.string.provider_mistral_note_en, Res.string.provider_mistral_note_zh)
        ModelProvider.Alibaba -> LocalizedText(Res.string.provider_alibaba_note_en, Res.string.provider_alibaba_note_zh)
        ModelProvider.Ollama -> LocalizedText(Res.string.provider_ollama_note_en, Res.string.provider_ollama_note_zh)
        ModelProvider.GLM -> LocalizedText(Res.string.provider_glm_note_en, Res.string.provider_glm_note_zh)
    },
)

@Composable
fun modelName(model: ModelOption): String = model.texts()?.first?.let { text(it) } ?: model.id

@Composable
fun modelDescription(model: ModelOption): String = model.texts()?.second?.let { text(it) } ?: providerNote(model.provider)

private fun ModelOption.texts(): Pair<LocalizedText, LocalizedText>? = when (id) {
    "gpt-4o-mini" -> LocalizedText(Res.string.model_gpt4o_mini_en, Res.string.model_gpt4o_mini_zh) to
        LocalizedText(Res.string.model_gpt4o_mini_desc_en, Res.string.model_gpt4o_mini_desc_zh)
    "gpt-4o" -> LocalizedText(Res.string.model_gpt4o_en, Res.string.model_gpt4o_zh) to
        LocalizedText(Res.string.model_gpt4o_desc_en, Res.string.model_gpt4o_desc_zh)
    "deepseek-v4-flash" -> LocalizedText(Res.string.model_deepseek_flash_en, Res.string.model_deepseek_flash_zh) to
        LocalizedText(Res.string.model_deepseek_flash_desc_en, Res.string.model_deepseek_flash_desc_zh)
    "deepseek-v4-pro" -> LocalizedText(Res.string.model_deepseek_pro_en, Res.string.model_deepseek_pro_zh) to
        LocalizedText(Res.string.model_deepseek_pro_desc_en, Res.string.model_deepseek_pro_desc_zh)
    "glm-5.1" -> LocalizedText(Res.string.model_glm_51_en, Res.string.model_glm_51_zh) to
        LocalizedText(Res.string.model_glm_51_desc_en, Res.string.model_glm_51_desc_zh)
    "glm-4.7-flashx" -> LocalizedText(Res.string.model_glm_flash_en, Res.string.model_glm_flash_zh) to
        LocalizedText(Res.string.model_glm_flash_desc_en, Res.string.model_glm_flash_desc_zh)
    else -> null
}

@Composable
fun protectionDescription(protection: SettingsProtection): String = text(
    when (protection) {
        SettingsProtection.AndroidKeystore -> LocalizedText(Res.string.protection_android_en, Res.string.protection_android_zh)
        SettingsProtection.IosKeychain -> LocalizedText(Res.string.protection_ios_en, Res.string.protection_ios_zh)
        SettingsProtection.DesktopAppData -> LocalizedText(Res.string.protection_desktop_en, Res.string.protection_desktop_zh)
        SettingsProtection.BrowserLocalStorage -> LocalizedText(Res.string.protection_web_en, Res.string.protection_web_zh)
        SettingsProtection.Transient -> LocalizedText(Res.string.protection_transient_en, Res.string.protection_transient_zh)
    },
)
