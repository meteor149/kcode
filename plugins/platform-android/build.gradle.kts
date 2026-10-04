plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.meteor.kcode.plugin.platform.android"
    compileSdk = 35
    packaging { jniLibs { useLegacyPackaging = true } }

    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "ai.meteor.kcode.plugin.NativeWindowTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    androidTestImplementation(project(":plugins:default-ui-api"))
    androidTestImplementation("org.jetbrains.compose.material3:material3:1.8.2")
    androidTestImplementation(project(":plugins:markdown"))
    androidTestImplementation(project(":plugins:agent-loop"))
    androidTestImplementation(project(":plugins:subagent-provider"))
    androidTestImplementation(project(":plugins:session-history"))
    androidTestImplementation(project(":plugins:message-codec"))
    androidTestImplementation(project(":plugins:model-settings"))
    androidTestImplementation(project(":plugins:localization"))
    androidTestImplementation(project(":plugins:conversation-export"))
    androidTestImplementation(project(":plugins:shell"))
    androidTestImplementation(project(":plugins:web-container"))
    androidTestImplementation(project(":plugins:web-search-provider"))
    androidTestImplementation(project(":plugins:web-search"))
    androidTestImplementation(project(":plugins:filesystem"))
    androidTestImplementation(project(":plugins:skill-tools"))
    androidTestImplementation(project(":plugins:artifact-tools"))
    androidTestImplementation(project(":plugins:llm"))
    androidTestImplementation(project(":plugins:goal"))
    androidTestImplementation(project(":plugins:schedule"))
    androidTestImplementation(project(":plugins:ui-pages"))
    androidTestImplementation(project(":plugins:ui-settings"))
    androidTestImplementation(project(":plugins:schedule-dispatch"))
    api(project(":plugins:api"))
    api(project(":plugins:runtime"))
    implementation(project(":plugins:interaction"))
    implementation(project(":plugins:skills"))
    implementation(project(":plugins:installation-store"))
    implementation(project(":plugins:history-repository"))
    implementation(project(":plugins:settings-repository"))
    implementation(project(":plugins:capability-providers"))
    implementation(project(":plugins:filesystem"))
    implementation(project(":plugins:shell"))
    implementation(project(":plugins:native-execution"))
    implementation(project(":plugins:native-filesystem"))
    implementation(project(":plugins:artifact-repository"))
    implementation(project(":plugins:conversation-export"))
    implementation(project(":plugins:native-notifications"))
    implementation(project(":plugins:web-container"))
    implementation(project(":plugins:web-search-provider"))
    implementation(project(":plugins:web-search"))
    implementation(project(":plugins:skill-tools"))
    implementation(project(":plugins:artifact-tools"))
    implementation(project(":plugins:conversation-overlay"))
    implementation(libs.cordis.hmr)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(project(":plugins:artifact-repository"))
    androidTestImplementation(project(":plugins:application"))
    androidTestImplementation(project(":plugins:conversation-execution"))
    androidTestImplementation(project(":plugins:settings-commands"))
    androidTestImplementation(project(":plugins:interaction"))
    androidTestImplementation("com.tencent:mmkv-kmp:2.4.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
