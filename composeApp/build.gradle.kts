import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
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
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "kcode.js"
            }
        }
        binaries.executable()
    }
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "KcodeShared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":historyStore"))
                api(project(":webSearch"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("ai.koog:agents-tools:1.0.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        val agentMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":h5Container"))
                implementation("ai.koog:koog-agents:1.0.0")
                implementation("ai.koog:agents-ext:1.0.0-beta")
                implementation("ai.koog:http-client-ktor:1.0.0")
                implementation("ai.koog:prompt-executor-deepseek-client:1.0.0-beta")
                implementation("ai.koog:prompt-executor-google-client:1.0.0-beta")
                implementation("ai.koog:prompt-executor-openrouter-client:1.0.0")
                implementation("ai.koog:prompt-executor-mistralai-client:1.0.0-beta")
                implementation("ai.koog:prompt-executor-dashscope-client:1.0.0-beta")
            }
        }
        val jvmAndAndroidMain by creating {
            dependsOn(agentMain)
            dependencies {
                implementation("androidx.datastore:datastore-preferences:1.2.1")
            }
        }
        val desktopMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("ai.koog:prompt-executor-bedrock-client:1.0.0")
            }
        }
        val androidMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("dev.rikka.shizuku:api:13.1.5")
                implementation("dev.rikka.shizuku:provider:13.1.5")
            }
        }
        val wasmJsMain by getting {
            dependsOn(agentMain)
        }
        val iosMain by creating {
            dependsOn(agentMain)
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
    }
}

android {
    namespace = "app.kcode.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 35
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "app.kcode.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("jdk.httpserver")
            packageName = "kcode"
            packageVersion = "1.0.0"
            description = "A calm, cross-platform AI workspace powered by Koog."
            vendor = "kcode"

            windows {
                iconFile.set(project.file("src/desktopMain/resources/kcode-icon.ico"))
            }
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/kcode-icon.icns"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/kcode-icon.png"))
            }
        }
    }
}
