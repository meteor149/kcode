plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.serialization")
}

// Compile the adapter's XML dictionary into its private bytecode. External JAR/APK loading
// therefore needs no host resource lookup and carries the labels from the selected package.
val modelLabelOutput = layout.buildDirectory.dir("generated/modelLabels/kotlin")
val generateModelLabels by tasks.registering {
    val dictionaries = fileTree("src/main/localization") { include("*.xml") }
    inputs.files(dictionaries)
    outputs.dir(modelLabelOutput)
    doLast {
        fun quoted(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val labels = linkedMapOf<String, String>()
        dictionaries.files.sortedBy { it.name }.forEach { dictionary ->
            val strings = factory.newDocumentBuilder().parse(dictionary).getElementsByTagName("string")
            repeat(strings.length) { index ->
                val string = strings.item(index) as org.w3c.dom.Element
                val key = string.getAttribute("name")
                check(labels.put(key, string.textContent) == null) { "Duplicate model label: $key" }
            }
        }
        val output = modelLabelOutput.get().file("ai/meteor/kcode/plugin/llm/BuiltinModelLabels.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package ai.meteor.kcode.plugin.llm")
            appendLine()
            appendLine("internal val BuiltinModelLabels: Map<String, String> = mapOf(")
            labels.forEach { (key, value) -> appendLine("    ${quoted(key)} to ${quoted(value)},") }
            appendLine(")")
        }, Charsets.UTF_8)
    }
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain { kotlin.srcDir(modelLabelOutput) }
        commonMain.dependencies {
            api(project(":plugins:api"))
            listOf("openai", "anthropic", "ollama", "openrouter").forEach {
                implementation("ai.koog:prompt-executor-$it-client:1.1.1")
            }
            listOf("deepseek", "google", "mistralai", "dashscope").forEach {
                implementation("ai.koog:prompt-executor-$it-client:1.1.1-beta")
            }
        }
        getByName("desktopMain").dependencies {
            implementation("ai.koog:prompt-executor-bedrock-client:1.1.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateModelLabels)
}

android {
    namespace = "ai.meteor.kcode.plugin.llm"
    compileSdk = 35

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The distribution and private-loading fixtures use the same vendor-owned dependency closure.
val modelVendors = mapOf(
    "OpenAI" to "openai", "AzureOpenAI" to "openai", "GLM" to "openai",
    "Anthropic" to "anthropic", "Google" to "google", "DeepSeek" to "deepseek",
    "OpenRouter" to "openrouter", "Bedrock" to "bedrock", "Mistral" to "mistralai",
    "Alibaba" to "dashscope", "Ollama" to "ollama",
)
val hostExportsFile = project(":plugins:api").file("src/commonMain/kotlin/ai/meteor/kcode/platform/PluginHostApiPackages.kt")
val hostExports = providers.provider {
    Regex("(?m)^\\s*\"([^\"]+)\"").findAll(hostExportsFile.readText())
        .map { it.groupValues[1].replace("\\$", "$").replace('.', '/') }.toList()
}
modelVendors.forEach { (provider, vendor) ->
    val privateDependencies = configurations.create("private${provider}Desktop")
    val version = if (vendor in setOf("google", "deepseek", "mistralai", "dashscope")) "1.1.1-beta" else "1.1.1"
    dependencies { add(privateDependencies.name, "ai.koog:prompt-executor-$vendor-client-jvm:$version") }
    tasks.register<Jar>("packaged${provider}DesktopJar") {
        dependsOn("desktopJar")
        archiveClassifier.set("$provider-packaged-desktop")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        inputs.file(hostExportsFile)
        from({ zipTree((tasks.getByName("desktopJar") as Jar).archiveFile.get().asFile) })
        from({ privateDependencies.files.sortedBy { it.name }.map { zipTree(it) } }) {
            exclude { entry ->
                val name = entry.relativePath.pathString
                hostExports.get().any { path ->
                    name.startsWith("$path/") || name == "$path.class" || name.startsWith("$path$")
                }
            }
        }
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "**/module-info.class")
    }
}
