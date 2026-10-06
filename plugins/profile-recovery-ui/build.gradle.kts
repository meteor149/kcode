plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Shared Compose resource readers use the host Android context. Keep this feature's
// XML-owned text defaults in its private code, independent of host APK resources.
val recoveryTextOutput = layout.buildDirectory.dir("generated/recoveryTexts/kotlin")
val generateRecoveryTexts = tasks.register("generateRecoveryTexts") {
    val dictionary = layout.projectDirectory.file("src/commonMain/composeResources/values/strings.xml")
    inputs.file(dictionary)
    outputs.dir(recoveryTextOutput)
    doLast {
        fun quoted(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val strings = factory.newDocumentBuilder().parse(dictionary.asFile).getElementsByTagName("string")
        val labels = linkedMapOf<String, String>()
        repeat(strings.length) { index ->
            val string = strings.item(index) as org.w3c.dom.Element
            val key = string.getAttribute("name")
            check(key.isNotBlank() && labels.put(key, string.textContent) == null) { "Invalid or duplicate Profile text: $key" }
        }
        val output = recoveryTextOutput.get().file("ai/meteor/kcode/plugin/recovery/RecoveryResourceStrings.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package ai.meteor.kcode.plugin.recovery")
            appendLine()
            appendLine("internal val RecoveryResourceStrings: Map<String, String> = mapOf(")
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
        commonMain { kotlin.srcDir(recoveryTextOutput) }
        commonMain.dependencies {
            api(project(":plugins:api"))
            implementation(project(":plugins:profile-management-ui"))
            implementation(project(":plugins:ui-profiles"))
            implementation(project(":plugins:runtime"))
            implementation(project(":libraries:ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        }
        commonTest.dependencies {
            implementation(project(":plugins:profiles"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateRecoveryTexts)
}

compose.resources { packageOfResClass = "ai.meteor.kcode.plugin.recovery.resources" }

android {
    namespace = "ai.meteor.kcode.plugin.profilerecoveryui"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
