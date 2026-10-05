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
    testImplementation(project(":plugins:model-settings"))
    testImplementation(project(":plugins:execution-settings"))
    testImplementation(project(":plugins:localization"))
    testImplementation(project(":plugins:conversation-export"))
    testImplementation(project(":plugins:shell"))
    testImplementation(project(":plugins:web-container"))
    testImplementation(project(":plugins:web-search"))
    testImplementation(project(":plugins:interaction-settings"))
    testImplementation(project(":plugins:filesystem"))
    testImplementation(project(":plugins:skill-tools"))
    testImplementation(project(":plugins:artifact-tools"))
    testImplementation(project(":plugins:llm"))
    testImplementation(project(":plugins:llm-service"))
    testImplementation(project(":plugins:goal"))
    testImplementation(project(":plugins:schedule"))
    api(project(":plugins:api"))
    api(project(":plugins:runtime"))
    implementation(project(":plugins:installation-store"))
    implementation(project(":plugins:package-provider"))
    testImplementation(project(":plugins:history-repository"))
    testImplementation(project(":plugins:settings-repository"))
    testImplementation(project(":plugins:capability-providers"))
    testImplementation(project(":plugins:native-execution"))
    testImplementation(project(":plugins:native-filesystem"))
    testImplementation(project(":plugins:artifact-repository"))

    testImplementation(project(":plugins:native-notifications"))
    compileOnly(project(":plugins:web-container"))
    compileOnly(project(":plugins:web-search"))
    implementation(libs.cordis.hmr)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation(project(":plugins:test-support"))
    testImplementation(project(":plugins:bundle-native"))
    testImplementation(project(":plugins:interaction"))
    testImplementation(project(":plugins:native-tool-approvals"))
    testImplementation(project(":plugins:skills"))
    testImplementation(project(":plugins:tools"))
    testImplementation(project(":plugins:system-prompt"))
    testImplementation(project(":plugins:continuations"))
    testImplementation(project(":plugins:settings-commands"))
    testImplementation(project(":plugins:subagents"))
    testImplementation(project(":plugins:installation-store"))
    testImplementation("ai.koog:agents-ext:1.1.1-beta")
    testImplementation(project(":plugins:application"))
    testImplementation(project(":plugins:conversation-execution"))
    testImplementation(project(":plugins:conversation-overlay"))
    testImplementation(project(":plugins:ui-pages"))
    testImplementation(project(":plugins:ui-contributions"))
    testImplementation(project(":plugins:default-ui-bridge"))

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":plugins:history-repository:packagedDesktopJar")
    listOf("OpenAI", "AzureOpenAI", "GLM", "Anthropic", "Google", "DeepSeek", "OpenRouter", "Bedrock", "Mistral", "Alibaba", "Ollama").forEach {
        dependsOn(":plugins:llm:packaged${it}DesktopJar")
    }
    systemProperty("kcode.llm.packaged.dir", project(":plugins:llm").layout.buildDirectory.dir("libs").get().asFile.absolutePath)
    systemProperty("kcode.history.packaged.jar", project(":plugins:history-repository")
        .layout.buildDirectory.file("libs/history-repository-packaged-desktop.jar").get().asFile.absolutePath)
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
    resources.srcDir(project(":distribution:packager").layout.buildDirectory.dir("bundled"))
}
tasks.processResources { dependsOn(":distribution:packager:stageBundledPlugins") }
