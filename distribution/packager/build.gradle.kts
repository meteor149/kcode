plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":plugins:package-provider"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("packagePlugin") {
    group = "distribution"
    description = "Package platform artifacts using packageId/version/entry and optional desktopArtifact/androidArtifact properties"
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.meteor.kcode.distribution.PluginPackagerKt")
    doFirst {
        fun property(name: String) = providers.gradleProperty(name).orNull ?: "-"
        args(property("packageId"), property("packageVersion"), property("packageEntry"),
            property("desktopArtifact"), property("desktopSdkAbi"), property("androidArtifact"),
            property("androidPackageName"), property("androidSdkAbi"), property("packageOutput"), property("packageConfiguration"), property("packageCapabilities"), property("androidEntry"), property("packageTargets"))
    }
}

fun androidArm64TargetsFile(name: String): String {
    val file = layout.buildDirectory.file("generated/targets/$name.json").get().asFile
    file.parentFile.mkdirs()
    file.writeText("""[{"system":"android","arch":["arm"],"bits":[64],"minSystemVersion":"15"}]""")
    return file.absolutePath
}

// Preparation releases for independent native execution fixtures.
tasks.register<JavaExec>("packageNativeSystemShell") {
    group = "distribution"
    description = "Build a bytecode-only Android system Shell archive without Ubuntu assets/JNI"
    dependsOn("classes", ":distribution:native-shell-android:assembleDebug",
        ":plugins:package-provider:generateAndroidPackageAbi")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.meteor.kcode.distribution.PluginPackagerKt")
    val artifact = rootProject.layout.buildDirectory.file("distribution/android/native-shell/outputs/apk/debug/native-shell-android-debug.apk")
    val abi = project(":plugins:package-provider").layout.buildDirectory.file("generated/packageAbi/android/sdk-abi-android.txt")
    val output = layout.buildDirectory.file("preparation/provider.shell.platform-1.0.0.kplugin")
    inputs.file(artifact)
    inputs.file(abi)
    outputs.file(output)
    outputs.file(output.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") })
    doFirst {
        args("provider.shell.platform", "@content:1.0.0", "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedShellPlugin",
            "-", "-", artifact.get().asFile.absolutePath, "ai.meteor.kcode.external.nativeshell",
            abi.get().asFile.absolutePath, output.get().asFile.absolutePath, "-", "shell")
    }
}

tasks.register<JavaExec>("packageNativeUbuntu") {
    group = "distribution"
    description = "Build an Android ARM64 Ubuntu execution archive from the independent APK"
    dependsOn("classes", ":distribution:native-execution-android:assembleDebug",
        ":plugins:package-provider:generateAndroidPackageAbi")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.meteor.kcode.distribution.PluginPackagerKt")
    val artifact = rootProject.layout.buildDirectory.file("distribution/android/native-execution/outputs/apk/debug/native-execution-android-debug.apk")
    val abi = project(":plugins:package-provider").layout.buildDirectory.file("generated/packageAbi/android/sdk-abi-android.txt")
    val output = layout.buildDirectory.file("preparation/provider.shell.ubuntu-1.0.0.kplugin")
    inputs.file(artifact)
    inputs.file(abi)
    outputs.file(output)
    outputs.file(output.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") })
    doFirst {
        args("provider.shell.ubuntu", "@content:1.0.0", "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedUbuntuShellPlugin",
            "-", "-", artifact.get().asFile.absolutePath, "ai.meteor.kcode.external.nativeexecution",
            abi.get().asFile.absolutePath, output.get().asFile.absolutePath, "-", "ubuntuShell", "-", androidArm64TargetsFile("preparation-ubuntu"))
    }
}

tasks.register<JavaExec>("packageMessageCodec") {
    group = "distribution"
    description = "Build a real dual-target message codec .kplugin with independent JAR/APK implementations"
    dependsOn("classes", ":plugins:message-codec:desktopJar", ":distribution:message-codec-android:assembleDebug",
        ":plugins:package-provider:generateDesktopPackageAbi", ":plugins:package-provider:generateAndroidPackageAbi")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.meteor.kcode.distribution.PluginPackagerKt")
    val output = layout.buildDirectory.file("packages/provider.message-codec.envelope-1.0.0.kplugin")
    inputs.files(project(":plugins:message-codec").layout.buildDirectory.dir("libs"))
    inputs.file(project(":distribution:message-codec-android").layout.buildDirectory.file("outputs/apk/debug/message-codec-android-debug.apk"))
    inputs.dir(project(":plugins:package-provider").layout.buildDirectory.dir("generated/packageAbi"))
    val releaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0")
    inputs.property("releaseVersion", releaseVersion)
    outputs.file(output)
    outputs.file(output.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") })
    doFirst {
        val desktop = project(":plugins:message-codec").layout.buildDirectory.dir("libs").get().asFile.listFiles().orEmpty().single { it.name.endsWith("-desktop.jar") }
        val android = project(":distribution:message-codec-android").layout.buildDirectory.file("outputs/apk/debug/message-codec-android-debug.apk").get().asFile
        val sdk = project(":plugins:package-provider").layout.buildDirectory.dir("generated/packageAbi").get().asFile
        args("provider.message-codec.envelope", "@content:${releaseVersion.get()}", "ai.meteor.kcode.plugin.messagecodec.MessageCodecProviderPlugin",
            desktop.absolutePath, sdk.resolve("desktop/sdk-abi-desktop.txt").absolutePath,
            android.absolutePath, "ai.meteor.kcode.external.messagecodec", sdk.resolve("android/sdk-abi-android.txt").absolutePath,
            output.get().asFile.absolutePath, "-", "messageCodec")
    }
}

data class BundledProvider(val module: String, val id: String, val entry: String, val capability: String,
    val androidEntry: String = entry, val desktopJarTask: String = "desktopJar", val desktop: Boolean = true,
    val android: Boolean = true, val androidModule: String = module, val androidArm64Only: Boolean = false)
val bundledProviders = listOf(
    BundledProvider("native-execution", "provider.shell.platform", "ai.meteor.kcode.plugin.nativeexecution.DesktopNativeShellPlugin", "shell",
        androidEntry = "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedShellPlugin", androidModule = "native-shell"),
    BundledProvider("execution-settings", "policy.shell-mode.platform", "ai.meteor.kcode.plugin.executionsettings.SettingsShellModePlugin", "shellMode", desktop = false),
    BundledProvider("native-execution", "provider.shell.ubuntu", "ai.meteor.kcode.plugin.nativeexecution.AndroidPackagedUbuntuShellPlugin", "ubuntuShell",
        desktop = false, androidArm64Only = true),
    BundledProvider("conversation-overlay", "core.conversation-overlays", "ai.meteor.kcode.plugin.overlay.NativeConversationOverlaysServicePlugin", "conversationOverlays"),
    BundledProvider("conversation-overlay", "provider.conversation-overlay.platform", "ai.meteor.kcode.plugin.overlay.AndroidNativeConversationOverlayPlugin", "conversationOverlays", desktop = false),
    BundledProvider("web-container", "feature.web-container", "ai.meteor.kcode.plugin.webcontainer.native.DesktopWebContainerFeaturePlugin", "webContainers,tools,uiSlots",
        androidEntry = "ai.meteor.kcode.plugin.webcontainer.native.AndroidWebContainerFeaturePlugin"),
    BundledProvider("web-search", "feature.web-search", "ai.meteor.kcode.plugin.websearch.WebSearchFeaturePlugin", "web,tools,searchSettings",
        desktopJarTask = "packagedDesktopJar"),
    BundledProvider("artifact-repository", "provider.artifacts.platform", "ai.meteor.kcode.plugin.artifacts.DesktopNativeArtifactsPlugin", "artifacts",
        androidEntry = "ai.meteor.kcode.plugin.artifacts.AndroidNativeArtifactsPlugin"),
    BundledProvider("history-repository", "provider.history.platform", "ai.meteor.kcode.plugin.history.DesktopNativeHistoryPlugin", "history",
        androidEntry = "ai.meteor.kcode.plugin.history.AndroidNativeHistoryPlugin", desktopJarTask = "packagedDesktopJar"),
    BundledProvider("llm", "provider.llm.koog.OpenAI", "ai.meteor.kcode.plugin.llm.OpenAIModelAdapterPlugin", "llm", desktopJarTask = "packagedOpenAIDesktopJar", androidModule = "llm-openai"),
    BundledProvider("llm", "provider.llm.koog.AzureOpenAI", "ai.meteor.kcode.plugin.llm.AzureOpenAIModelAdapterPlugin", "llm", desktopJarTask = "packagedAzureOpenAIDesktopJar", androidModule = "llm-azureopenai"),
    BundledProvider("llm", "provider.llm.koog.Anthropic", "ai.meteor.kcode.plugin.llm.AnthropicModelAdapterPlugin", "llm", desktopJarTask = "packagedAnthropicDesktopJar", androidModule = "llm-anthropic"),
    BundledProvider("llm", "provider.llm.koog.Google", "ai.meteor.kcode.plugin.llm.GoogleModelAdapterPlugin", "llm", desktopJarTask = "packagedGoogleDesktopJar", androidModule = "llm-google"),
    BundledProvider("llm", "provider.llm.koog.DeepSeek", "ai.meteor.kcode.plugin.llm.DeepSeekModelAdapterPlugin", "llm", desktopJarTask = "packagedDeepSeekDesktopJar", androidModule = "llm-deepseek"),
    BundledProvider("llm", "provider.llm.koog.OpenRouter", "ai.meteor.kcode.plugin.llm.OpenRouterModelAdapterPlugin", "llm", desktopJarTask = "packagedOpenRouterDesktopJar", androidModule = "llm-openrouter"),
    BundledProvider("llm", "provider.llm.koog.Bedrock", "ai.meteor.kcode.plugin.llm.BedrockModelAdapterPlugin", "llm", desktopJarTask = "packagedBedrockDesktopJar", android = false),
    BundledProvider("llm", "provider.llm.koog.Mistral", "ai.meteor.kcode.plugin.llm.MistralModelAdapterPlugin", "llm", desktopJarTask = "packagedMistralDesktopJar", androidModule = "llm-mistral"),
    BundledProvider("llm", "provider.llm.koog.Alibaba", "ai.meteor.kcode.plugin.llm.AlibabaModelAdapterPlugin", "llm", desktopJarTask = "packagedAlibabaDesktopJar", androidModule = "llm-alibaba"),
    BundledProvider("llm", "provider.llm.koog.Ollama", "ai.meteor.kcode.plugin.llm.OllamaModelAdapterPlugin", "llm", desktopJarTask = "packagedOllamaDesktopJar", androidModule = "llm-ollama"),
    BundledProvider("llm", "provider.llm.koog.GLM", "ai.meteor.kcode.plugin.llm.GLMModelAdapterPlugin", "llm", desktopJarTask = "packagedGLMDesktopJar", androidModule = "llm-glm"),
    BundledProvider("interaction-settings", "provider.interaction.platform", "ai.meteor.kcode.plugin.SettingsToolInteractionPlugin", "interaction"),
    BundledProvider("settings-repository", "provider.settings.platform", "ai.meteor.kcode.plugin.settingsstorage.DesktopNativeSettingsPlugin", "settings",
        androidEntry = "ai.meteor.kcode.plugin.settingsstorage.AndroidNativeSettingsPlugin"),
    BundledProvider("llm-service", "core.llm", "ai.meteor.kcode.plugin.LlmServicePlugin", "llm"),
    BundledProvider("tools", "core.tools", "ai.meteor.kcode.plugin.ToolsServicePlugin", "tools"),
    BundledProvider("system-prompt", "core.system-prompt", "ai.meteor.kcode.plugin.SystemPromptServicePlugin", "systemPrompt"),
    BundledProvider("system-prompt", "provider.prompt.default", "ai.meteor.kcode.plugin.DefaultSystemPromptPlugin", "systemPrompt"),
    BundledProvider("continuations", "core.continuations", "ai.meteor.kcode.plugin.ContinuationServicePlugin", "continuations"),
    BundledProvider("model-settings", "provider.model-settings.catalog", "ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin", "modelSettings"),
    BundledProvider("artifact-tools", "feature.artifacts", "ai.meteor.kcode.plugin.ArtifactFeaturePlugin", "artifact,tools,uiSlots"),
    BundledProvider("schedule", "feature.schedule", "ai.meteor.kcode.plugin.ScheduleFeaturePlugin", "schedules,tools,schedule.dispatch"),
    BundledProvider("goal", "feature.goal", "ai.meteor.kcode.plugin.GoalFeaturePlugin", "goals,tools,conversationCommands,continuations,uiSlots"),
    BundledProvider("subagents", "feature.subagents", "ai.meteor.kcode.plugin.SubagentFeaturePlugin", "subagents,tools,continuations,uiSlots"),
    BundledProvider("settings-commands", "consumer.settings.commands", "ai.meteor.kcode.plugin.settingscommands.SettingsCommandsPlugin", "settingsCommands"),
    BundledProvider("ui-contributions", "core.ui-contributions", "ai.meteor.kcode.plugin.UiContributionsServicePlugin", "uiContributions"),
    BundledProvider("default-ui-bridge", "core.ui-slots", "ai.meteor.kcode.plugin.UiSlotsServicePlugin", "uiSlots"),
    BundledProvider("ui-pages", "provider.ui.conversation.transcript", "ai.meteor.kcode.plugin.DefaultConversationTranscriptUiPlugin", "uiSlots,conversation.transcript"),
    BundledProvider("ui-pages", "provider.ui.layout", "ai.meteor.kcode.plugin.DefaultLayoutUiPlugin", "uiSlots,application.layout"),
    BundledProvider("ui-pages", "provider.ui.sidebar", "ai.meteor.kcode.plugin.DefaultSidebarUiPlugin", "uiSlots,page.sidebar"),
    BundledProvider("ui-pages", "provider.ui.chat", "ai.meteor.kcode.plugin.DefaultChatUiPlugin", "uiSlots,page.chat"),
    BundledProvider("ui-pages", "provider.ui.conversation.standalone", "ai.meteor.kcode.plugin.DefaultStandaloneConversationUiPlugin", "uiSlots,conversation.standalone"),
    BundledProvider("ui-pages", "provider.ui.settings", "ai.meteor.kcode.plugin.DefaultSettingsUiPlugin", "uiSlots,page.settings"),
    BundledProvider("ui-pages", "provider.ui.theme", "ai.meteor.kcode.plugin.DefaultThemeUiPlugin", "uiSlots,theme"),
    BundledProvider("ui-pages", "provider.ui.navigation.chat", "ai.meteor.kcode.plugin.DefaultChatNavigationPlugin", "uiSlots,navigation"),
    BundledProvider("ui-pages", "provider.ui.message.user", "ai.meteor.kcode.plugin.DefaultUserMessagePresentationPlugin", "uiSlots,message.renderer"),
    BundledProvider("ui-pages", "provider.ui.message.assistant", "ai.meteor.kcode.plugin.DefaultAssistantMessagePresentationPlugin", "uiSlots,message.renderer"),
    BundledProvider("ui-pages", "provider.ui.message.error", "ai.meteor.kcode.plugin.DefaultErrorMessagePresentationPlugin", "uiSlots,message.renderer"),
    BundledProvider("ui-pages", "provider.ui.tool.default", "ai.meteor.kcode.plugin.DefaultToolUsePresentationPlugin", "uiSlots,tool.renderer"),
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
    BundledProvider("skill-tools", "consumer.tools.skill", "ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin", "skill,tools"),
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
    BundledProvider("native-filesystem", "provider.fs.platform", "ai.meteor.kcode.plugin.nativefilesystem.DesktopNativeFileSystemPlugin", "fs,skillWorkspace",
        androidEntry = "ai.meteor.kcode.plugin.nativefilesystem.AndroidNativeFileSystemPlugin", desktopJarTask = "packagedDesktopJar"),
)
val providerPackageTasks = bundledProviders.map { provider ->
    val taskName = "package" + provider.id.split('.', '-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    tasks.register<JavaExec>(taskName) {
        group = "distribution"
        require(provider.desktop || provider.android) { "A bundled package needs at least one platform" }
        dependsOn("classes",
            ":plugins:package-provider:generateDesktopPackageAbi", ":plugins:package-provider:generateAndroidPackageAbi")
        if (provider.desktop) dependsOn(":plugins:${provider.module}:${provider.desktopJarTask}")
        if (provider.android) dependsOn(":distribution:${provider.androidModule}-android:assembleDebug")
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("ai.meteor.kcode.distribution.PluginPackagerKt")
        val plugin = project(":plugins:${provider.module}")
        val androidPlugin = project(":distribution:${provider.androidModule}-android")
        val sdk = project(":plugins:package-provider").layout.buildDirectory.dir("generated/packageAbi")
        val output = layout.buildDirectory.file("packages/${provider.id}-1.0.0.kplugin")
        val version = providers.gradleProperty("releaseVersion").orElse("1.0.0")
        if (provider.desktop) inputs.file(providers.provider { (plugin.tasks.getByName(provider.desktopJarTask) as Jar).archiveFile.get().asFile })
        if (provider.android) inputs.file(androidPlugin.layout.buildDirectory.file("outputs/apk/debug/${provider.androidModule}-android-debug.apk"))
        inputs.dir(sdk)
        inputs.property("releaseVersion", version)
        inputs.property("descriptor", provider.toString())
        outputs.file(output)
        outputs.file(output.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") })
        doFirst {
            val jar = if (provider.desktop) (plugin.tasks.getByName(provider.desktopJarTask) as Jar).archiveFile.get().asFile.absolutePath else "-"
            val apk = androidPlugin.layout.buildDirectory.file("outputs/apk/debug/${provider.androidModule}-android-debug.apk").get().asFile
            args(provider.id, "@content:${version.get()}", provider.entry, jar,
                if (provider.desktop) sdk.get().file("desktop/sdk-abi-desktop.txt").asFile.absolutePath else "-",
                if (provider.android) apk.absolutePath else "-", if (provider.android) "ai.meteor.kcode.external.${provider.androidModule.replace("-", "")}" else "-",
                if (provider.android) sdk.get().file("android/sdk-abi-android.txt").asFile.absolutePath else "-",
                output.get().asFile.absolutePath, "-", provider.capability, provider.androidEntry, if (provider.androidArm64Only) androidArm64TargetsFile(provider.id) else "-")
        }
    }
}

tasks.register<Sync>("stageBundledPlugins") {
    group = "distribution"
    description = "Stage the native hosts' trusted default package catalog and independent archives"
    dependsOn("packageMessageCodec", providerPackageTasks)
    val packages = layout.buildDirectory.dir("packages")
    val ids = listOf("provider.message-codec.envelope") + bundledProviders.map { it.id }
    inputs.property("bundledIds", ids)
    inputs.property("catalogFormat", "1")
    from(packages) { include(ids.map { "$it-1.0.0.kplugin" }) }
    into(layout.buildDirectory.dir("bundled/kcode/plugins"))
    doLast {
        project.javaexec {
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("ai.meteor.kcode.distribution.PluginPackagerKt")
            args("--catalog", destinationDir.absolutePath)
        }
    }
}
