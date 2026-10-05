package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation

private data class NativeProfileLayer(val id: String, val modules: Set<String>)

/** Distribution declarations, independent of module naming conventions and platform availability. */
private val nativeProfileLayers = listOf(
    NativeProfileLayer("kcode.base", setOf(
        "core.conversation-overlays",
        "core.tools",
        "core.system-prompt",
        "core.conversation-commands",
        "core.llm",
        "core.continuations",
        "core.ui-contributions",
        "provider.settings.platform",
        "provider.history.platform",
        "feature.localization",
    )),
    NativeProfileLayer("kcode.agent", setOf(
        "provider.conversation-overlay.platform",
        "provider.model-settings.catalog",
        "feature.subagents",
        "provider.prompt.default",
        "provider.skills.platform",
        "feature.schedule",
        "feature.goal",
        "provider.interaction.platform",
        "feature.web-search",
        "consumer.settings.commands",
        "provider.message-codec.envelope",
        "provider.sessions.history",
        "provider.notifications.platform",
        "feature.conversation-export",
        "provider.generation",
        "provider.conversation-execution.history",
        "provider.agent-loop.koog",
        "provider.shell.platform",
        "provider.shell.ubuntu",
        "policy.shell-mode.platform",
        "consumer.tools.shell",
        "consumer.tools.filesystem",
        "consumer.tools.skill",
        "consumer.tools.android-shell",
        "consumer.tools.ubuntu-shell",
        "provider.tool-approvals.native",
        "policy.notifications.permission.android",
        "provider.generation.foreground.android",
        "provider.fs.platform",
        "provider.llm.koog.OpenAI",
        "provider.llm.koog.AzureOpenAI",
        "provider.llm.koog.Anthropic",
        "provider.llm.koog.Google",
        "provider.llm.koog.DeepSeek",
        "provider.llm.koog.OpenRouter",
        "provider.llm.koog.Bedrock",
        "provider.llm.koog.Mistral",
        "provider.llm.koog.Alibaba",
        "provider.llm.koog.Ollama",
        "provider.llm.koog.GLM",
    )),
    NativeProfileLayer("kcode.default-ui", setOf(
        "feature.markdown",
        "core.ui-slots",
        "provider.ui.compose",
        "provider.ui.conversation.transcript",
        "provider.ui.layout",
        "provider.ui.sidebar",
        "provider.ui.chat",
        "provider.ui.conversation.standalone",
        "provider.ui.settings",
        "provider.ui.theme",
        "provider.ui.navigation.chat",
        "provider.ui.message.user",
        "provider.ui.message.assistant",
        "provider.ui.message.error",
        "provider.ui.tool.default",
    )),
)

/** Shipped product layers are data; additional available code must be selected explicitly. */
fun nativeProfileBundles(moduleIds: List<String>): List<ProfileBundle> {
    require(moduleIds.distinct().size == moduleIds.size && moduleIds.all { it.isNotBlank() }) {
        "Native default modules must have distinct nonempty identities"
    }
    val declared = nativeProfileLayers.flatMap { it.modules }
    check(declared.distinct().size == declared.size) { "Native modules must belong to exactly one shipped layer" }
    require(moduleIds.all { it in declared }) {
        "Native default modules require explicit Bundle declarations: ${moduleIds.filterNot { it in declared }}"
    }
    return nativeProfileLayers.map { layer ->
        ProfileBundle(id = layer.id, version = "1", patches = listOf(ProfileOperation.Insert(
            moduleIds.filter { it in layer.modules }.map { ProfileEntry(it, it) },
        )))
    }
}

fun nativeProfileTemplate(bundles: List<ProfileBundle>, includeDefaults: Boolean = true) = ProfileDefinition(
    id = "native",
    bundles = if (includeDefaults) bundles.map { ProfileBundleReference(it.id, it.version) } else emptyList(),
    // Migration preserves the former native data locations. New user profiles default to isolation.
    dataScope = ProfileDataScope(settings = "legacy", history = "legacy"),
)

val NativeProfileInfrastructureAliases = mapOf(
    "provider.plugin-installations.platform" to emptySet<String>(),
    "provider.plugin-packages.platform" to emptySet(),
)
