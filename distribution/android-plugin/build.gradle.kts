plugins { id("com.android.application") }

// SDK-only providers use their real compiled Android library, not copied common source.
val artifactModule = project.name.removeSuffix("-android")
val providerModule = if (artifactModule == "native-shell") "native-execution" else if (artifactModule.startsWith("llm-") && artifactModule != "llm-service") "llm" else artifactModule
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("distribution/android/$artifactModule"))

// Ordinary Shell runs platform processes and must not inherit Ubuntu's ARM64 payload.
// Keep the compiled provider code intact; remove deployment-only Ubuntu assets/JNI.
val systemShellLibrary = if (artifactModule == "native-shell") tasks.register<Zip>("prepareSystemShellLibrary") {
    dependsOn(":plugins:native-execution:bundleDebugAar")
    val library = project(":plugins:native-execution").layout.buildDirectory.file("outputs/aar/native-execution-debug.aar")
    inputs.file(library)
    from(library.map { zipTree(it.asFile) })
    exclude("assets/**", "jni/**")
    destinationDirectory.set(layout.buildDirectory.dir("preparedLibrary"))
    archiveFileName.set("native-execution-system.aar")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
} else null

android {
    namespace = "ai.meteor.kcode.external.${artifactModule.replace("-", "") }"
    compileSdk = 35
    defaultConfig {
        applicationId = namespace
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (providerModule == "native-execution") {
        sourceSets.getByName("main").manifest.srcFile("src/nativeExecution/AndroidManifest.xml")
        packaging { jniLibs { useLegacyPackaging = true } }
    }
}

dependencies {
    if (systemShellLibrary != null) {
        implementation(files(systemShellLibrary.flatMap { it.archiveFile }))
    } else {
        implementation(project(":plugins:$providerModule")) { isTransitive = false }
    }
    if (providerModule == "llm") {
        val vendor = mapOf("azureopenai" to "openai", "glm" to "openai", "mistral" to "mistralai", "alibaba" to "dashscope")
            .getOrDefault(artifactModule.removePrefix("llm-"), artifactModule.removePrefix("llm-"))
        val selected = if (artifactModule == "llm") setOf("openai", "anthropic", "ollama", "openrouter", "google", "deepseek", "mistralai", "dashscope") else setOf(vendor)
        selected.forEach { client ->
            val version = if (client in setOf("google", "deepseek", "mistralai", "dashscope")) "1.1.1-beta" else "1.1.1"
            implementation("ai.koog:prompt-executor-$client-client:$version") { isTransitive = false }
        }
        // Several vendors subclass the OpenAI-compatible base; it is private, too.
        if (selected.any { it in setOf("openai", "openrouter", "mistralai", "dashscope", "deepseek") }) {
            implementation("ai.koog:prompt-executor-openai-client-base:1.1.1") { isTransitive = false }
        }
    }
    if (providerModule == "web-container") {
        // WebKit adapters are private; Core/native component contracts remain SDK peers.
        implementation("androidx.webkit:webkit:1.13.0") { isTransitive = false }
    }
    if (providerModule == "native-filesystem") {
        implementation(project(":plugins:capability-providers")) { isTransitive = false }
    }
    if (providerModule == "web-search") {
        // Shared Kotlin, coroutines, serialization, IO and Okio come from the SDK.
        listOf("ktor-client-okhttp", "ktor-client-content-negotiation", "ktor-serialization-kotlinx-json").forEach {
            implementation("io.ktor:$it:3.3.3") {
                exclude(group = "org.jetbrains.kotlin")
                exclude(group = "org.jetbrains.kotlinx")
                exclude(group = "com.squareup.okio")
                exclude(group = "org.jetbrains", module = "annotations")
            }
        }
    }
    if (providerModule == "history-repository") {
        implementation("androidx.room3:room3-runtime:3.0.1") { isTransitive = false }
        implementation("androidx.room3:room3-common:3.0.1") { isTransitive = false }
        implementation("androidx.collection:collection:1.5.0") { isTransitive = false }
    }
    if (providerModule == "native-execution") {
        // Shizuku is a host bridge. Archive codecs are private implementation dependencies.
        implementation("org.apache.commons:commons-compress:1.27.1")
        implementation("org.tukaani:xz:1.10")
    }
    compileOnly(project(":plugins:api"))
    compileOnly(project(":plugins:default-ui-api"))
    compileOnly(project(":libraries:ui"))
}
