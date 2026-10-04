plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Compile the provider's XML dictionary into its private bytecode. External JAR/APK loading
// therefore needs no host resource lookup and carries the labels from the selected package.
val translationOutput = layout.buildDirectory.dir("generated/translations/kotlin")
val generateTranslations by tasks.registering {
    val dictionaries = fileTree("src/main/localization") { include("*.xml") }
    inputs.files(dictionaries)
    outputs.dir(translationOutput)
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
                check(labels.put(key, string.textContent) == null) { "Duplicate translation key: $key" }
            }
        }
        val output = translationOutput.get().file("ai/meteor/kcode/plugin/localization/BuiltinTranslations.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package ai.meteor.kcode.plugin.localization")
            appendLine()
            appendLine("internal val BuiltinTranslations: Map<String, String> = mapOf(")
            labels.forEach { (key, value) -> appendLine("    ${quoted(key)} to ${quoted(value)},") }
            appendLine(")")
        }, Charsets.UTF_8)
    }
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain { kotlin.srcDir(translationOutput) }
        commonMain.dependencies {
            api(project(":plugins:api"))
            api(project(":plugins:default-ui-api"))
            implementation(compose.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateTranslations)
}

android {
    namespace = "ai.meteor.kcode.plugin.localization"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
