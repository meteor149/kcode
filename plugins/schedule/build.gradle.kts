plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

// Compile the dispatcher's XML dictionary into its private bytecode. External JAR/APK loading
// therefore needs no host resource lookup and carries the labels from the selected package.
val dispatchLabelOutput = layout.buildDirectory.dir("generated/dispatchLabels/kotlin")
val generateDispatchLabels by tasks.registering {
    val dictionaries = fileTree("src/main/localization") { include("*.xml") }
    inputs.files(dictionaries)
    outputs.dir(dispatchLabelOutput)
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
                check(labels.put(key, string.textContent) == null) { "Duplicate dispatch label: $key" }
            }
        }
        val output = dispatchLabelOutput.get().file("ai/meteor/kcode/plugin/scheduledispatch/DispatchLabels.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package ai.meteor.kcode.plugin.scheduledispatch")
            appendLine()
            appendLine("internal val DispatchLabels: Map<String, String> = mapOf(")
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
        commonMain { kotlin.srcDir(dispatchLabelOutput) }
        commonMain.dependencies {
            api(project(":plugins:api"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        }
        getByName("desktopTest").dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation(kotlin("test"))
            implementation(project(":plugins:test-support"))
        }
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.schedule"
    compileSdk = 35

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateDispatchLabels)
}
