plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(from = rootProject.file("gradle/feature-ui-texts.gradle.kts"))

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
        commonMain { kotlin.srcDir(layout.buildDirectory.dir("generated/uiTexts/kotlin")) }
        commonMain.dependencies {
            api(project(":plugins:api"))
            api(project(":plugins:default-ui-api"))
            implementation(project(":libraries:ui"))
            implementation(compose.material3)
            implementation(compose.foundation)
            implementation(compose.runtime)
            implementation("io.ktor:ktor-client-core:3.3.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        }
        commonTest.dependencies {
            implementation(project(":plugins:test-support"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        getByName("desktopMain").dependencies { implementation("io.ktor:ktor-client-cio:3.3.3") }
        getByName("androidMain").dependencies { implementation("io.ktor:ktor-client-okhttp:3.3.3") }
        getByName("desktopTest").dependencies {
            implementation("io.ktor:ktor-client-mock:3.3.3")
        }
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.websearch"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Package all search implementations together; only SDK/framework types are shared.
val privateDesktopTransport = configurations.create("privateDesktopTransport")
dependencies {
    privateDesktopTransport("io.ktor:ktor-client-cio-jvm:3.3.3")
    privateDesktopTransport("io.ktor:ktor-client-content-negotiation-jvm:3.3.3")
    privateDesktopTransport("io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.3")
}
tasks.register<Jar>("packagedDesktopJar") {
    dependsOn("desktopJar")
    archiveClassifier.set("packaged-desktop")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from({ zipTree((tasks.getByName("desktopJar") as Jar).archiveFile.get().asFile) })
    from({
        privateDesktopTransport.resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group in setOf("io.ktor", "org.slf4j") }
            .sortedBy { it.moduleVersion.id.toString() }.map { zipTree(it.file) }
    })
    exclude("META-INF/MANIFEST.MF", "META-INF/versions/9/module-info.class")
}
