plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
        commonMain.dependencies {
            api("androidx.annotation:annotation:1.9.1")
            // SQLite/JNI is a host peer; Room and repository implementations are plugin-private.
            api("androidx.sqlite:sqlite-bundled:2.7.0")
            api("androidx.sqlite:sqlite-async:2.7.0")
            api(compose.runtime)
            api(compose.ui)
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api(libs.cordis.core)
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            // SDK peers expose protocol/framework identities, never vendor client implementations.
            listOf("agents-tools", "koog-agents", "agents-ext").forEach { artifact ->
                api("ai.koog:$artifact:${if (artifact == "agents-ext") "1.1.1-beta" else "1.1.1"}") {
                    listOf("openai", "openai-client-base", "anthropic", "bedrock", "ollama").forEach { vendor ->
                        val module = if (vendor == "openai-client-base") "prompt-executor-$vendor" else "prompt-executor-$vendor-client"
                        listOf("", "-jvm", "-android").forEach { variant ->
                            exclude(group = "ai.koog", module = module + variant)
                        }
                    }
                }
            }
            api("io.github.oshai:kotlin-logging:8.0.01")
        }
        getByName("desktopMain").dependencies {
            api("androidx.datastore:datastore-preferences:1.2.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
        }
        getByName("androidMain").dependencies {
            // Core's legacy android.support identities are already shared with native host components.
            api("androidx.core:core-ktx:1.15.0")
            api("androidx.versionedparcelable:versionedparcelable:1.1.1")
            implementation("androidx.activity:activity-compose:1.9.3")
            api("com.tencent:mmkv-kmp:2.4.1")
            // Shared Binder/client identities must survive withdrawal of execution providers.
            api("dev.rikka.shizuku:api:13.1.5")
            api("dev.rikka.shizuku:provider:13.1.5")
        }
        commonTest.dependencies {
            implementation(project(":plugins:test-support"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.api"
    compileSdk = 35
    buildFeatures { aidl = true }
    packaging { jniLibs { useLegacyPackaging = true } }

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
