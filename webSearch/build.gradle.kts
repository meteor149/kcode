plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

android {
    namespace = "app.kcode.search"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    androidTarget()
    jvm("desktop")
    wasmJs { browser() }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "KcodeWebSearch"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("ai.koog:agents-tools:1.0.0")
            implementation("io.ktor:ktor-client-core:3.3.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.3.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.3.3") }
        getByName("desktopMain").dependencies { implementation("io.ktor:ktor-client-cio:3.3.3") }
        wasmJsMain.dependencies { implementation("io.ktor:ktor-client-js:3.3.3") }
        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
    }
}
