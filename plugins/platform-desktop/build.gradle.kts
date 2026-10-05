plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Native rendering belongs to the desktop host, rather than neutral shared contracts.
    runtimeOnly(compose.desktop.currentOs)
    testImplementation(project(":plugins:default-ui-api"))
    testImplementation("org.jetbrains.compose.material3:material3:1.8.2")
    testImplementation(project(":plugins:markdown"))
    testImplementation(project(":plugins:agent-loop"))
    testImplementation(project(":plugins:session-history"))
    testImplementation(project(":plugins:message-codec"))
    testImplementation(project(":plugins:llm-core"))
    testImplementation(project(":plugins:native-execution"))
    testImplementation(project(":plugins:localization"))
    testImplementation(project(":plugins:conversation-export"))
    testImplementation(project(":plugins:shell"))
    testImplementation(project(":plugins:web-search"))
    testImplementation(project(":plugins:interaction"))
    testImplementation(project(":plugins:filesystem"))
    testImplementation(project(":plugins:llm:openai"))
    testImplementation(project(":plugins:llm:azure-openai"))
    testImplementation(project(":plugins:llm:anthropic"))
    testImplementation(project(":plugins:llm:google"))
    testImplementation(project(":plugins:llm:deepseek"))
    testImplementation(project(":plugins:llm:openrouter"))
    testImplementation(project(":plugins:llm:bedrock"))
    testImplementation(project(":plugins:llm:mistral"))
    testImplementation(project(":plugins:llm:alibaba"))
    testImplementation(project(":plugins:llm:ollama"))
    testImplementation(project(":plugins:llm:glm"))
    testImplementation(project(":plugins:goal"))
    testImplementation(project(":plugins:schedule"))
    api(project(":plugins:api"))
    api(project(":plugins:runtime"))
    implementation(project(":plugins:installation-store"))
    implementation(project(":plugins:package-provider"))
    testImplementation(project(":plugins:history-repository"))
    testImplementation(project(":plugins:settings"))
    testImplementation(project(":plugins:capability-providers"))

    testImplementation(project(":plugins:native-notifications"))
    compileOnly(project(":plugins:web-search"))
    implementation(libs.cordis.hmr)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation(project(":plugins:test-support"))
    testImplementation(project(":plugins:bundle-native"))
    testImplementation(project(":plugins:native-tool-approvals"))
    testImplementation(project(":plugins:skills"))
    testImplementation(project(":plugins:tools"))
    testImplementation(project(":plugins:system-prompt"))
    testImplementation(project(":plugins:continuations"))
    testImplementation(project(":plugins:subagents"))
    testImplementation(project(":plugins:installation-store"))
    testImplementation("ai.koog:agents-ext:1.1.1-beta")
    testImplementation(project(":plugins:application"))
    testImplementation(project(":plugins:conversation-execution"))
    testImplementation(project(":plugins:conversation-overlay"))
    testImplementation(project(":plugins:ui-pages"))
    testImplementation(project(":plugins:ui-messages"))
    testImplementation(project(":plugins:ui-theme"))
    testImplementation(project(":plugins:ui-shell"))
    testImplementation(project(":plugins:ui-contributions"))
    testImplementation(project(":plugins:default-ui-bridge"))

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":plugins:history-repository:prepareProviderHistoryPlatformDesktopJar")
    dependsOn(":plugins:llm:openai:prepareProviderLlmKoogOpenAIDesktopJar")
    systemProperty("kcode.llm.packaged.OpenAI", project(":plugins:llm:openai").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-OpenAI/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:azure-openai:prepareProviderLlmKoogAzureOpenAIDesktopJar")
    systemProperty("kcode.llm.packaged.AzureOpenAI", project(":plugins:llm:azure-openai").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-AzureOpenAI/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:anthropic:prepareProviderLlmKoogAnthropicDesktopJar")
    systemProperty("kcode.llm.packaged.Anthropic", project(":plugins:llm:anthropic").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-Anthropic/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:google:prepareProviderLlmKoogGoogleDesktopJar")
    systemProperty("kcode.llm.packaged.Google", project(":plugins:llm:google").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-Google/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:deepseek:prepareProviderLlmKoogDeepSeekDesktopJar")
    systemProperty("kcode.llm.packaged.DeepSeek", project(":plugins:llm:deepseek").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-DeepSeek/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:openrouter:prepareProviderLlmKoogOpenRouterDesktopJar")
    systemProperty("kcode.llm.packaged.OpenRouter", project(":plugins:llm:openrouter").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-OpenRouter/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:bedrock:prepareProviderLlmKoogBedrockDesktopJar")
    systemProperty("kcode.llm.packaged.Bedrock", project(":plugins:llm:bedrock").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-Bedrock/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:mistral:prepareProviderLlmKoogMistralDesktopJar")
    systemProperty("kcode.llm.packaged.Mistral", project(":plugins:llm:mistral").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-Mistral/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:alibaba:prepareProviderLlmKoogAlibabaDesktopJar")
    systemProperty("kcode.llm.packaged.Alibaba", project(":plugins:llm:alibaba").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-Alibaba/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:ollama:prepareProviderLlmKoogOllamaDesktopJar")
    systemProperty("kcode.llm.packaged.Ollama", project(":plugins:llm:ollama").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-Ollama/desktop/plugin.jar").get().asFile.absolutePath)
    dependsOn(":plugins:llm:glm:prepareProviderLlmKoogGLMDesktopJar")
    systemProperty("kcode.llm.packaged.GLM", project(":plugins:llm:glm").layout.buildDirectory.file("cordis/artifacts/provider-llm-koog-GLM/desktop/plugin.jar").get().asFile.absolutePath)
    systemProperty("kcode.history.packaged.jar", project(":plugins:history-repository")
        .layout.buildDirectory.file("cordis/artifacts/provider-history-platform/desktop/plugin.jar").get().asFile.absolutePath)
    val productionClasspath = layout.buildDirectory.file("test-inputs/production-classpath.txt")
    systemProperty("kcode.production.classpath.file", productionClasspath.get().asFile.absolutePath)
    doFirst {
        productionClasspath.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sourceSets.main.get().runtimeClasspath.asPath)
        }
        // Resolve test dependencies after all projects have configured their source sets.
        systemProperty("kcode.private.search.classpath", configurations.testRuntimeClasspath.get().files
            .filter { it.name.startsWith("ktor-") || it.name.startsWith("slf4j-") }
            .joinToString(File.pathSeparator) { it.absolutePath })
    }
}

sourceSets.main {
    resources.srcDir(rootProject.layout.buildDirectory.dir("bundled"))
}
tasks.processResources { dependsOn(":stageBundledPlugins") }
