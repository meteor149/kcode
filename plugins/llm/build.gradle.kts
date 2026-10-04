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
            implementation("ai.koog:prompt-executor-deepseek-client:1.1.1-beta")
            implementation("ai.koog:prompt-executor-google-client:1.1.1-beta")
            implementation("ai.koog:prompt-executor-openrouter-client:1.1.1")
            implementation("ai.koog:prompt-executor-mistralai-client:1.1.1-beta")
            implementation("ai.koog:prompt-executor-dashscope-client:1.1.1-beta")
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
