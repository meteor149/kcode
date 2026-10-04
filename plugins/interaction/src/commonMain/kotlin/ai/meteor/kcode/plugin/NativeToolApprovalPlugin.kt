package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ConfirmationDialogRequest
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.plugin.api.KcodeToolApprovals
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

fun toolApprovalConfig(title: String, message: String, allow: String, deny: String): String = buildJsonObject {
    put("title", title)
    put("message", message)
    put("allow", allow)
    put("deny", deny)
}.toString()

internal expect fun formatApprovalText(template: String, vararg arguments: String): String

private data class ApprovalStrings(val title: String, val message: String, val allow: String, val deny: String)

private fun parseToolApprovalConfig(config: String): ApprovalStrings {
    val text = Json.parseToJsonElement(config).jsonObject
    fun value(key: String): String {
        val primitive = requireNotNull(text[key]?.jsonPrimitive) { "Missing approval $key" }
        require(primitive.isString && primitive.content.isNotBlank()) { "Approval $key must be a nonblank string" }
        return primitive.content
    }
    val strings = ApprovalStrings(value("title"), value("message"), value("allow"), value("deny"))
    formatApprovalText(strings.title, "tool")
    formatApprovalText(strings.message, "description", "input")
    return strings
}

/** Explicit deployment text is usable without a localization provider. */
class NativeToolApprovalPlugin : Plugin<String> {
    override val name = "kcode-native-tool-approvals"
    override val config = ConfigValidator<String> { value -> parseToolApprovalConfig(value); value }
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val strings = parseToolApprovalConfig(config)
        applyNativeApproval(ctx, effect) { request ->
            ConfirmationDialogRequest(
                title = formatApprovalText(strings.title, request.name),
                message = formatApprovalText(strings.message, request.description.ifBlank { request.name }.take(2_048), request.input.take(8_192)),
                confirmLabel = strings.allow, cancelLabel = strings.deny,
            )
        }
    }
}

/** Default text belongs to the current dictionary, with explicit service dependencies. */
class LocalizedNativeToolApprovalPlugin : Plugin<Unit> {
    override val name = "kcode-localized-native-tool-approvals"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeSettings.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val catalog = ctx.require(KcodeLocalization.Key).catalog
        val settings = ctx.require(KcodeSettings.Key).store
        applyNativeApproval(ctx, effect) { request ->
            val language = checkNotNull(catalog.snapshot()).selectLanguage(settings.load().language)
            ConfirmationDialogRequest(
                title = catalog.translate(language, UiText.ToolConfirmationTitle, request.name),
                message = catalog.translate(language, UiText.ToolConfirmationMessage,
                    request.description.ifBlank { request.name }.take(2_048), request.input.take(8_192)),
                confirmLabel = catalog.translate(language, UiText.ToolConfirmationAllow),
                cancelLabel = catalog.translate(language, UiText.ToolConfirmationDeny),
            )
        }
    }
}

private suspend fun applyNativeApproval(
    ctx: Context,
    effect: EffectScope,
    dialogFor: suspend (ToolApprovalRequest) -> ConfirmationDialogRequest,
) {
    val dialogs = requireNotNull(PluginHostInputs.current(ctx, effect)?.confirmationDialogs()) {
        "Native tool approvals require a confirmation dialog host"
    }
    val owner = PluginOperationOwner("Native tool approvals")
    effect.collect { owner.close() }
    KcodeToolApprovals(ctx, ToolCallApprover { request -> owner.run { dialogs.confirm(dialogFor(request)) } })
}

class SettingsToolInteractionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-settings-tool-interaction"
    override val inject = dependencies(KcodeSettings.Key, KcodeToolApprovals.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val store = ctx.require(KcodeSettings.Key).store
        applyToolInteraction(ctx, effect) { ToolPermissionMode.fromCode(store.load().toolPermissionMode) ?: ToolPermissionMode.Ask }
    }
}

class HostModeToolInteractionPlugin : Plugin<suspend () -> ToolPermissionMode> {
    override val name = "kcode-host-mode-tool-interaction"
    override val inject = dependencies(KcodeToolApprovals.Key)
    override suspend fun apply(ctx: Context, config: suspend () -> ToolPermissionMode, effect: EffectScope) =
        applyToolInteraction(ctx, effect, config)
}

private fun applyToolInteraction(ctx: Context, effect: EffectScope, mode: suspend () -> ToolPermissionMode) {
    val approver = ctx.require(KcodeToolApprovals.Key).approver
    val owner = PluginOperationOwner("tool interaction policy")
    effect.collect { owner.close() }
    KcodeInteraction(ctx, InteractionPolicy(
        permissionModeProvider = { owner.run { mode() } },
        approver = ToolCallApprover { request -> owner.run { approver.approve(request) } },
    ))
}
