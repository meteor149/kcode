import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.cordis.packager.CordisPackagesExtension
import org.cordis.packager.PluginCatalogTask
import org.cordis.packages.PackageTarget
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("io.github.meteor149.cordis.packager") version "0.0.1-SNAPSHOT" apply false
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("jvm") version "2.3.21" apply false
    kotlin("android") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("com.android.application") version "8.10.0" apply false
    id("com.android.library") version "8.10.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
    id("androidx.room3") version "3.0.1" apply false
}

// Complete AARs must be available for private project dependencies as well as releases.
subprojects {
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("io.github.meteor149.cordis.packager")
    }
}

// Kcode SDK policy and metadata remain product build configuration, not a packager plugin.
val kcodeHostModules = setOf(
    "project::plugins:api",
    "project::plugins:default-ui-api",
    "project::libraries:ui",
    "io.github.meteor149:core",
    "io.github.meteor149:hmr",
    "io.github.meteor149:loader",
    "io.github.meteor149:packages",
)
val kcodeHostExports = Regex("(?m)^\\s*\"([^\"]+)\"")
    .findAll(file("plugins/api/src/commonMain/kotlin/ai/meteor/kcode/platform/PluginHostApiPackages.kt").readText())
    .map { it.groupValues[1].replace("\\$", "$") }
    .toList()
fun isKcodeClassExport(value: String): Boolean =
    value.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
val kcodeSharedPackages = kcodeHostExports.filterNot(::isKcodeClassExport).toSet()
val kcodeSharedClasses = kcodeHostExports.filter(::isKcodeClassExport).toSet()

@CacheableTask
abstract class GenerateKcodePackageMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sdkAbiDescriptor: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiVersionSource: RegularFileProperty

    @get:Input
    abstract val capabilities: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val abi = MessageDigest.getInstance("SHA-256")
            .digest(sdkAbiDescriptor.get().asFile.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val source = apiVersionSource.get().asFile.readText()
        val apiVersion = Regex("const val CurrentPluginApiVersion = (\\d+)")
            .find(source)?.groupValues?.get(1)
            ?: error("CurrentPluginApiVersion is missing from ${apiVersionSource.get().asFile}")
        val capabilityJson = capabilities.get().distinct().sorted()
            .joinToString(",", transform = ::jsonString)
        val metadata = """{"ai.meteor.kcode":{"pluginApi":$apiVersion,"runtimeAbi":"$abi","capabilities":[$capabilityJson]}}"""
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(metadata + "\n")
        }
    }

    private fun jsonString(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""
}


fun kcodeVariantMetadata(
    owner: Project,
    release: String,
    platform: String,
    capabilities: Set<String>,
): Provider<RegularFile> {
    require(platform == "desktop" || platform == "android") { "Unsupported Kcode package platform: $platform" }
    val suffix = ("$release-$platform").split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty)
        .joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val sdkProject = owner.rootProject.project(":plugins:package-provider")
    val abiTask = "generate${platform.replaceFirstChar(Char::uppercase)}PackageAbi"
    val descriptor = sdkProject.layout.buildDirectory.file("generated/packageAbi/$platform/sdk-abi-$platform.txt")
    val apiSource = owner.rootProject.file("plugins/api/src/commonMain/kotlin/ai/meteor/kcode/plugin/AgentPluginManager.kt")
    val task = owner.tasks.register(
        "generateKcodePackageMetadata$suffix",
        GenerateKcodePackageMetadata::class.java,
    ) {
        dependsOn(":plugins:package-provider:$abiTask")
        sdkAbiDescriptor.set(descriptor)
        apiVersionSource.set(apiSource)
        this.capabilities.set(capabilities.toList())
        outputFile.set(owner.layout.buildDirectory.file("generated/kcodePackageMetadata/$release/$platform.json"))
    }
    return task.flatMap { it.outputFile }
}

data class BundledProvider(val module: String, val id: String, val entry: String, val capability: String,
    val androidEntry: String = entry, val desktop: Boolean = true,
    val android: Boolean = true, val androidPackageSuffix: String = module, val androidArm64Only: Boolean = false)
val bundledProviders = listOf(
    BundledProvider("message-codec", "provider.message-codec.envelope",
        "ai.meteor.kcode.plugin.messagecodec.MessageCodecProviderPlugin", "messageCodec"),
    BundledProvider("native-execution", "provider.shell.platform", "ai.meteor.kcode.plugin.nativeexecution.DesktopNativeShellPlugin", "shell",
        androidEntry = "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedShellPlugin", androidPackageSuffix = "native-shell"),
    BundledProvider("native-execution", "policy.shell-mode.platform", "ai.meteor.kcode.plugin.executionsettings.SettingsShellModePlugin", "shellMode", desktop = false),
    BundledProvider("native-execution", "provider.shell.ubuntu", "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedUbuntuShellPlugin", "ubuntuShell",
        desktop = false, androidArm64Only = true),
    BundledProvider("conversation-overlay", "core.conversation-overlays", "ai.meteor.kcode.plugin.overlay.NativeConversationOverlaysServicePlugin", "conversationOverlays"),
    BundledProvider("conversation-overlay", "provider.conversation-overlay.platform", "ai.meteor.kcode.plugin.overlay.AndroidNativeConversationOverlayPlugin", "conversationOverlays", desktop = false),
    BundledProvider("web-search", "feature.web-search", "ai.meteor.kcode.plugin.websearch.WebSearchFeaturePlugin", "web,tools,searchSettings"),
    BundledProvider("history-repository", "provider.history.platform", "ai.meteor.kcode.plugin.history.DesktopNativeHistoryPlugin", "history",
        androidEntry = "ai.meteor.kcode.plugin.history.AndroidNativeHistoryPlugin"),
    BundledProvider("llm:openai", "provider.llm.koog.OpenAI", "ai.meteor.kcode.plugin.llm.OpenAIModelAdapterPlugin", "llm", androidPackageSuffix = "llm-openai"),
    BundledProvider("llm:azure-openai", "provider.llm.koog.AzureOpenAI", "ai.meteor.kcode.plugin.llm.AzureOpenAIModelAdapterPlugin", "llm", androidPackageSuffix = "llm-azureopenai"),
    BundledProvider("llm:anthropic", "provider.llm.koog.Anthropic", "ai.meteor.kcode.plugin.llm.AnthropicModelAdapterPlugin", "llm", androidPackageSuffix = "llm-anthropic"),
    BundledProvider("llm:google", "provider.llm.koog.Google", "ai.meteor.kcode.plugin.llm.GoogleModelAdapterPlugin", "llm", androidPackageSuffix = "llm-google"),
    BundledProvider("llm:deepseek", "provider.llm.koog.DeepSeek", "ai.meteor.kcode.plugin.llm.DeepSeekModelAdapterPlugin", "llm", androidPackageSuffix = "llm-deepseek"),
    BundledProvider("llm:openrouter", "provider.llm.koog.OpenRouter", "ai.meteor.kcode.plugin.llm.OpenRouterModelAdapterPlugin", "llm", androidPackageSuffix = "llm-openrouter"),
    BundledProvider("llm:bedrock", "provider.llm.koog.Bedrock", "ai.meteor.kcode.plugin.llm.BedrockModelAdapterPlugin", "llm", android = false),
    BundledProvider("llm:mistral", "provider.llm.koog.Mistral", "ai.meteor.kcode.plugin.llm.MistralModelAdapterPlugin", "llm", androidPackageSuffix = "llm-mistral"),
    BundledProvider("llm:alibaba", "provider.llm.koog.Alibaba", "ai.meteor.kcode.plugin.llm.AlibabaModelAdapterPlugin", "llm", androidPackageSuffix = "llm-alibaba"),
    BundledProvider("llm:ollama", "provider.llm.koog.Ollama", "ai.meteor.kcode.plugin.llm.OllamaModelAdapterPlugin", "llm", androidPackageSuffix = "llm-ollama"),
    BundledProvider("llm:glm", "provider.llm.koog.GLM", "ai.meteor.kcode.plugin.llm.GLMModelAdapterPlugin", "llm", androidPackageSuffix = "llm-glm"),
    BundledProvider("interaction", "provider.interaction.platform", "ai.meteor.kcode.plugin.SettingsToolInteractionPlugin", "interaction"),
    BundledProvider("settings", "provider.settings.platform", "ai.meteor.kcode.plugin.settingsstorage.DesktopNativeSettingsPlugin", "settings",
        androidEntry = "ai.meteor.kcode.plugin.settingsstorage.AndroidNativeSettingsPlugin"),
    BundledProvider("llm-core", "core.llm", "ai.meteor.kcode.plugin.LlmServicePlugin", "llm"),
    BundledProvider("tools", "core.tools", "ai.meteor.kcode.plugin.ToolsServicePlugin", "tools"),
    BundledProvider("system-prompt", "core.system-prompt", "ai.meteor.kcode.plugin.SystemPromptServicePlugin", "systemPrompt"),
    BundledProvider("system-prompt", "provider.prompt.default", "ai.meteor.kcode.plugin.DefaultSystemPromptPlugin", "systemPrompt"),
    BundledProvider("continuations", "core.continuations", "ai.meteor.kcode.plugin.ContinuationServicePlugin", "continuations"),
    BundledProvider("llm-core", "provider.model-settings.catalog", "ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin", "modelSettings"),
    BundledProvider("schedule", "feature.schedule", "ai.meteor.kcode.plugin.ScheduleFeaturePlugin", "schedules,tools,schedule.dispatch"),
    BundledProvider("goal", "feature.goal", "ai.meteor.kcode.plugin.GoalFeaturePlugin", "goals,tools,conversationCommands,continuations,uiSlots"),
    BundledProvider("subagents", "feature.subagents", "ai.meteor.kcode.plugin.SubagentFeaturePlugin", "subagents,tools,continuations,uiSlots"),
    BundledProvider("settings", "consumer.settings.commands", "ai.meteor.kcode.plugin.settingscommands.SettingsCommandsPlugin", "settingsCommands"),
    BundledProvider("ui-contributions", "core.ui-contributions", "ai.meteor.kcode.plugin.UiContributionsServicePlugin", "uiContributions"),
    BundledProvider("default-ui-bridge", "core.ui-slots", "ai.meteor.kcode.plugin.UiSlotsServicePlugin", "uiSlots"),
    BundledProvider("ui-messages", "provider.ui.conversation.transcript", "ai.meteor.kcode.plugin.DefaultConversationTranscriptUiPlugin", "uiSlots,conversation.transcript"),
    BundledProvider("ui-shell", "provider.ui.layout", "ai.meteor.kcode.plugin.DefaultLayoutUiPlugin", "uiSlots,application.layout"),
    BundledProvider("ui-shell", "provider.ui.sidebar", "ai.meteor.kcode.plugin.DefaultSidebarUiPlugin", "uiSlots,page.sidebar"),
    BundledProvider("ui-pages", "provider.ui.chat", "ai.meteor.kcode.plugin.DefaultChatUiPlugin", "uiSlots,page.chat"),
    BundledProvider("ui-pages", "provider.ui.conversation.standalone", "ai.meteor.kcode.plugin.DefaultStandaloneConversationUiPlugin", "uiSlots,conversation.standalone"),
    BundledProvider("ui-shell", "provider.ui.settings", "ai.meteor.kcode.plugin.DefaultSettingsUiPlugin", "uiSlots,page.settings"),
    BundledProvider("ui-theme", "provider.ui.theme", "ai.meteor.kcode.plugin.DefaultThemeUiPlugin", "uiSlots,theme"),
    BundledProvider("ui-pages", "provider.ui.navigation.chat", "ai.meteor.kcode.plugin.DefaultChatNavigationPlugin", "uiSlots,navigation"),
    BundledProvider("ui-messages", "provider.ui.message.user", "ai.meteor.kcode.plugin.DefaultUserMessagePresentationPlugin", "uiSlots,message.renderer"),
    BundledProvider("ui-messages", "provider.ui.message.assistant", "ai.meteor.kcode.plugin.DefaultAssistantMessagePresentationPlugin", "uiSlots,message.renderer"),
    BundledProvider("ui-messages", "provider.ui.message.error", "ai.meteor.kcode.plugin.DefaultErrorMessagePresentationPlugin", "uiSlots,message.renderer"),
    BundledProvider("ui-messages", "provider.ui.tool.default", "ai.meteor.kcode.plugin.DefaultToolUsePresentationPlugin", "uiSlots,tool.renderer"),
    BundledProvider("application", "provider.ui.compose", "ai.meteor.kcode.plugin.DefaultApplicationUiPlugin", "applicationUi"),
    BundledProvider("markdown", "feature.markdown", "ai.meteor.kcode.plugin.markdown.MarkdownFeaturePlugin", "markdown,uiSlots"),
    BundledProvider("localization", "feature.localization", "ai.meteor.kcode.plugin.localization.LocalizationFeaturePlugin", "localization,uiSlots"),
    BundledProvider("session-history", "provider.sessions.history", "ai.meteor.kcode.plugin.SessionHistoryProviderPlugin", "sessions"),
    BundledProvider("conversation-execution", "core.conversation-commands", "ai.meteor.kcode.plugin.ConversationCommandsServicePlugin", "conversationCommands"),
    BundledProvider("conversation-execution", "provider.generation", "ai.meteor.kcode.plugin.GenerationProviderPlugin", "generation"),
    BundledProvider("conversation-execution", "provider.conversation-execution.history", "ai.meteor.kcode.plugin.ConversationExecutionProviderPlugin", "conversationExecution"),
    BundledProvider("agent-loop", "provider.agent-loop.koog", "ai.meteor.kcode.plugin.KoogAgentLoopPlugin", "agents,agentLoop"),
    BundledProvider("skills", "provider.skills.platform", "ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin", "skills"),
    BundledProvider("filesystem", "consumer.tools.filesystem", "ai.meteor.kcode.plugin.feature.FilesystemToolConsumerPlugin", "fs,tools"),
    BundledProvider("skills", "consumer.tools.skill", "ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin", "skill,tools"),
    BundledProvider("shell", "consumer.tools.shell", "ai.meteor.kcode.plugin.feature.DesktopShellToolConsumerPlugin", "shell,tools", android = false),
    BundledProvider("shell", "consumer.tools.android-shell", "ai.meteor.kcode.plugin.feature.DefaultAndroidShellToolConsumerPlugin", "shell,tools", desktop = false),
    BundledProvider("shell", "consumer.tools.ubuntu-shell", "ai.meteor.kcode.plugin.feature.DefaultUbuntuShellToolConsumerPlugin", "ubuntuShell,tools", desktop = false),
    BundledProvider("native-tool-approvals", "provider.tool-approvals.native", "ai.meteor.kcode.plugin.LocalizedNativeToolApprovalPlugin", "toolApprovals"),
    BundledProvider("conversation-export", "feature.conversation-export", "ai.meteor.kcode.plugin.export.ConversationExportFeaturePlugin", "conversationImageRendering,conversationImageSaving,conversationExport,uiSlots"),
    BundledProvider("native-notifications", "provider.notifications.platform", "ai.meteor.kcode.plugin.notifications.DesktopNativeNotificationsPlugin", "scheduledTaskNotifications",
        androidEntry = "ai.meteor.kcode.plugin.notifications.LocalizedAndroidNativeNotificationsPlugin"),
    BundledProvider("native-notifications", "policy.notifications.permission.android", "ai.meteor.kcode.plugin.notifications.AndroidNotificationPermissionPlugin", "uiSlots",
        desktop = false),
    BundledProvider("native-notifications", "provider.generation.foreground.android", "ai.meteor.kcode.plugin.notifications.LocalizedAndroidGenerationForegroundPlugin", "generation",
        desktop = false),
    BundledProvider("filesystem", "provider.fs.platform", "ai.meteor.kcode.plugin.nativefilesystem.DesktopNativeFileSystemPlugin", "fs,skillWorkspace",
        androidEntry = "ai.meteor.kcode.plugin.nativefilesystem.AndroidNativeFileSystemPlugin"),
)

val bundledPlugins = configurations.create("bundledPlugins") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0")
val unitConfiguration = """{"ai.meteor.kcode":{"configuration":{"kind":"unit"}}}"""

// Exclude the SDK closure, retaining any components also reached through private roots.
// Host-owned components still exclude their resources/JNI even when clients depend on them.
fun hostComponents(owner: Project, platform: String) = owner.providers.provider {
    val configuration = if (platform == "desktop") "desktopRuntimeClasspath" else "debugRuntimeClasspath"
    val graph = owner.configurations.getByName(configuration).incoming.resolutionResult
    val sdk = kcodeHostModules
    fun identity(component: ResolvedComponentResult): String = when (val id = component.id) {
        is ModuleComponentIdentifier -> "${id.group}:${id.module}"
        is ProjectComponentIdentifier -> "project:${id.projectPath}"
        else -> id.displayName
    }
    fun shared(component: ResolvedComponentResult): Boolean {
        if (identity(component) in sdk) return true
        val module = component.moduleVersion ?: return false
        val group = module.group
        return group.startsWith("org.jetbrains.kotlin") ||
            group.startsWith("org.jetbrains.compose") || group.startsWith("androidx.compose") ||
            group.startsWith("androidx.activity") || group.startsWith("androidx.lifecycle") ||
            group.startsWith("androidx.savedstate") || group.startsWith("androidx.sqlite") ||
            group.startsWith("androidx.datastore") || group in setOf(
                "androidx.annotation", "androidx.core", "androidx.versionedparcelable",
                "org.jetbrains.skiko", "com.tencent", "dev.rikka.shizuku", "io.github.oshai",
                "com.squareup.okio", "org.jetbrains", "io.github.meteor149",
            ) || group == "ai.koog" && !module.name.startsWith("prompt-executor-")
    }
    fun closure(roots: List<ResolvedComponentResult>, stopAtSdk: Boolean = false): Set<String> {
        val visited = mutableSetOf<String>()
        fun visit(component: ResolvedComponentResult) {
            // Private implementation modules may depend directly on shared framework modules.
            // Their host-owned closure must not become private just because it is reached here.
            if (stopAtSdk && shared(component)) return
            if (!visited.add(identity(component))) return
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { visit(it.selected) }
        }
        roots.forEach(::visit)
        return visited
    }
    val roots = graph.root.dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }
    val hostClosure = closure(roots.filter { shared(it) })
    val privateClosure = closure(roots.filterNot { shared(it) }, stopAtSdk = true)
    (hostClosure - privateClosure) + graph.allComponents.filter(::shared).map(::identity)
}

bundledProviders.forEach { provider ->
    evaluationDependsOn(":plugins:${provider.module}")
    val owner = project(":plugins:${provider.module}")
    val releaseName = provider.id.replace('.', '-')
    val suffix = releaseName.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    owner.extensions.getByType<CordisPackagesExtension>().releases.register(releaseName) {
        packageId.set(provider.id)
        packageVersion.set(releaseVersion)
        contentVersion.set(true)
        manifestExtensions.set(unitConfiguration)
        fun configureVariant(platform: String, entry: String) {
            variants.register(platform) {
                entryPoint.set(entry)
                sharedPackages.addAll(kcodeSharedPackages)
                sharedClasses.addAll(kcodeSharedClasses)
                ignoreMultiReleaseEntries.set(true)
                hostModules.addAll(kcodeHostModules)
                hostModules.addAll(hostComponents(owner, platform))
                extensionsFile.set(kcodeVariantMetadata(owner, releaseName, platform, provider.capability.split(',').toSet()))
                if (platform == "desktop") {
                    jvmTarget.set("desktop")
                    runtimeMinVersion.set("17")
                    listOf("windows", "macos", "linux").forEach { target(PackageTarget(it, listOf("arm", "x86"))) }
                } else {
                    androidVariant.set("debug")
                    androidPackageName.set("ai.meteor.kcode.external.${provider.androidPackageSuffix.replace("-", "")}")
                    runtimeMinVersion.set("35")
                    target(if (provider.androidArm64Only) PackageTarget("android", listOf("arm"), bits = listOf(64), minSystemVersion = "15")
                        else PackageTarget("android", listOf("arm", "x86"), minSystemVersion = "15"))
                    if (provider.module == "native-execution") {
                        androidManifest.set(owner.layout.projectDirectory.file("src/packaged/AndroidManifest.xml"))
                        if (!provider.androidArm64Only) excludedPayloadPaths.addAll("assets", "lib")
                    }
                }
            }
        }
        if (provider.desktop) configureVariant("desktop", provider.entry)
        if (provider.android) configureVariant("android", provider.androidEntry)
    }
    dependencies.add(bundledPlugins.name, dependencies.project(mapOf("path" to owner.path, "configuration" to "cordis${suffix}Elements")))

}

tasks.register<PluginCatalogTask>("stageBundledPlugins") {
    group = "distribution"
    description = "Verify and stage the native hosts' selected Cordis package artifacts"
    archives.from(bundledPlugins)
    destinationDirectory.set(layout.buildDirectory.dir("bundled/kcode/plugins"))
}


tasks.register("packagePlugins") {
    group = "distribution"
    description = "Build the native product's selected Cordis plugin releases"
    dependsOn(bundledPlugins)
}
