import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room3")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val configuredReleaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0")
val configuredMacReleaseVersion = configuredReleaseVersion.map { version ->
    val components = version.split('.')
    val major = components.getOrNull(0)?.toIntOrNull() ?: 0
    if (major > 0) {
        version
    } else {
        val minor = components.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = components.getOrNull(2)?.toIntOrNull() ?: 0
        "1.$minor.$patch"
    }
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
        useEsModules()
        browser {
            commonWebpackConfig {
                outputFileName = "kcode.js"
            }
        }
        binaries.executable()
    }
    listOf(
        iosArm64(),
        iosX64(),
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
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.compose.ui:ui-backhandler:1.8.2")
                implementation(compose.components.resources)
                api(project(":extensions:webContainer"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        val roomMain by creating {
            dependsOn(commonMain)
            dependencies {
                api("androidx.room3:room3-runtime:3.0.1")
            }
        }
        val nativeSqliteMain by creating {
            dependsOn(roomMain)
            dependencies {
                implementation("androidx.sqlite:sqlite-bundled:2.7.0")
            }
        }
        val agentMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("dev.chrisbanes.haze:haze:1.6.0")
                implementation("ai.koog:agents-tools:1.1.1")
                implementation("ai.koog:koog-agents:1.1.1")
                implementation("ai.koog:agents-ext:1.1.1-beta")
                implementation("ai.koog:http-client-ktor:1.1.1")
                implementation("ai.koog:prompt-executor-deepseek-client:1.1.1-beta")
                implementation("ai.koog:prompt-executor-google-client:1.1.1-beta")
                implementation("ai.koog:prompt-executor-openrouter-client:1.1.1")
                implementation("ai.koog:prompt-executor-mistralai-client:1.1.1-beta")
                implementation("ai.koog:prompt-executor-dashscope-client:1.1.1-beta")
                implementation("io.ktor:ktor-client-core:3.3.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
            }
        }
        val mobileMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("com.tencent:mmkv-kmp:2.4.1")
            }
        }
        val desktopMain by getting {
            dependsOn(agentMain)
            dependsOn(roomMain)
            dependsOn(nativeSqliteMain)
            dependencies {
                implementation("androidx.datastore:datastore-preferences:1.2.1")
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("ai.koog:prompt-executor-bedrock-client:1.1.1")
                implementation("io.ktor:ktor-client-cio:3.3.3")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation("ai.koog:agents-tools:1.1.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("io.ktor:ktor-client-mock:3.3.3")
            }
        }
        val androidMain by getting {
            dependsOn(agentMain)
            dependsOn(mobileMain)
            dependsOn(roomMain)
            dependsOn(nativeSqliteMain)
            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("dev.rikka.shizuku:api:13.1.5")
                implementation("dev.rikka.shizuku:provider:13.1.5")
                implementation("io.ktor:ktor-client-okhttp:3.3.3")
            }
        }
        val wasmJsMain by getting {
            dependsOn(agentMain)
            dependsOn(roomMain)
            dependencies {
                implementation(project(":apps:web:sqliteWasmWorker"))
                implementation("io.ktor:ktor-client-js:3.3.3")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
        val iosMain by creating {
            dependsOn(agentMain)
            dependsOn(mobileMain)
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.3.3")
            }
        }
        val iosRoomMain by creating {
            dependsOn(iosMain)
            dependsOn(roomMain)
            dependsOn(nativeSqliteMain)
        }
        getByName("iosArm64Main").dependsOn(iosRoomMain)
        getByName("iosX64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosRoomMain)
    }
}

dependencies {
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.1")
    add("kspDesktop", "androidx.room3:room3-compiler:3.0.1")
    add("kspIosArm64", "androidx.room3:room3-compiler:3.0.1")
    add("kspIosX64", "androidx.room3:room3-compiler:3.0.1")
    add("kspIosSimulatorArm64", "androidx.room3:room3-compiler:3.0.1")
    add("kspWasmJs", "androidx.room3:room3-compiler:3.0.1")
}

room3 {
    schemaDirectory(layout.projectDirectory.dir("schemas"))
}

android {
    namespace = "ai.meteor.kcode.shared"
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
        mainClass = "ai.meteor.kcode.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("jdk.httpserver", "java.net.http")
            packageName = "kcode"
            packageVersion = configuredReleaseVersion.get()
            description = "A calm, cross-platform AI workspace powered by Koog."
            vendor = "kcode"

            windows {
                iconFile.set(project.file("src/desktopMain/resources/kcode-icon.ico"))
            }
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/kcode-icon.icns"))
                packageVersion = configuredMacReleaseVersion.get()
                dmgPackageVersion = configuredMacReleaseVersion.get()
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/kcode-icon.png"))
            }
        }
    }
}
