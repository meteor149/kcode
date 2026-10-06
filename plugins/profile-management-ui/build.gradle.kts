plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val managerTextOutput = layout.buildDirectory.dir("generated/profileManagerTexts/kotlin")
val generateManagerTexts = tasks.register("generateManagerTexts") {
    val dictionary = layout.projectDirectory.file("src/commonMain/composeResources/values/strings.xml")
    inputs.file(dictionary)
    outputs.dir(managerTextOutput)
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
            check(key.isNotBlank() && labels.put(key, string.textContent) == null) { "Invalid or duplicate manager text: $key" }
        }
        val output = managerTextOutput.get().file("ai/meteor/kcode/plugin/managementui/ProfileManagerTexts.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package ai.meteor.kcode.plugin.managementui")
            appendLine()
            appendLine("internal val ProfileManagerResourceStrings: Map<String, String> = mapOf(")
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
        val jvmMain by creating { dependsOn(commonMain.get()) }
        getByName("desktopMain").dependsOn(jvmMain)
        androidMain.get().dependsOn(jvmMain)
        commonMain {
            kotlin.srcDir(managerTextOutput)
            dependencies {
                api(project(":plugins:api"))
                implementation(project(":libraries:ui"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            }
        }
        androidMain.dependencies { implementation("androidx.activity:activity-compose:1.9.3") }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateManagerTexts)
}

compose.resources { packageOfResClass = "ai.meteor.kcode.plugin.managementui.resources" }

android {
    namespace = "ai.meteor.kcode.plugin.profilemanagementui"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
