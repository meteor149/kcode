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
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.layout.buildDirectory.dir("bundled"))
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(":stageBundledPlugins")
}

val nativeExecutionTestAssets = layout.buildDirectory.dir("generated/nativeExecutionTestAssets")
val stageNativeExecutionTestApk = tasks.register<Copy>("stageNativeExecutionTestApk") {
    dependsOn(":plugins:native-execution:prepareProviderShellUbuntuAndroidApk")
    from(project(":plugins:native-execution").layout.buildDirectory.file("cordis/artifacts/provider-shell-ubuntu/android/plugin.apk"))
    into(nativeExecutionTestAssets)
    rename { "native-execution.apk" }
}
android.sourceSets.getByName("androidTest").assets.srcDir(nativeExecutionTestAssets)
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(stageNativeExecutionTestApk)
}

val bundledTestPackages = layout.buildDirectory.dir("generated/bundledTestPackages")
val stageBundledTestPackages = tasks.register<Sync>("stageBundledTestPackages") {
    from(rootProject.configurations.getByName("bundledPlugins"))
    into(bundledTestPackages)
}
android.sourceSets.getByName("androidTest").assets.srcDir(bundledTestPackages)
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(stageBundledTestPackages)
}

// AGP lint tasks read generated main and test assets as well as merged assets.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(":stageBundledPlugins", stageNativeExecutionTestApk, stageBundledTestPackages)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":plugins:bundle-native"))
    implementation("androidx.core:core-ktx:1.15.0")
    androidTestImplementation(project(":plugins:test-support"))
    androidTestImplementation(project(":plugins:default-ui-api"))
    androidTestImplementation("org.jetbrains.compose.material3:material3:1.8.2")
    androidTestImplementation(project(":plugins:markdown"))
    androidTestImplementation(project(":plugins:agent-loop"))
    androidTestImplementation(project(":plugins:session-history"))
    androidTestImplementation(project(":plugins:message-codec"))
    androidTestImplementation(project(":plugins:llm-core"))
    androidTestImplementation(project(":plugins:native-execution"))
    androidTestImplementation(project(":plugins:tools"))
    androidTestImplementation(project(":plugins:system-prompt"))
    androidTestImplementation(project(":plugins:continuations"))
    androidTestImplementation(project(":plugins:subagents"))
    androidTestImplementation(project(":plugins:localization"))
    androidTestImplementation(project(":plugins:conversation-export"))
    androidTestImplementation(project(":plugins:shell"))
    androidTestImplementation(project(":plugins:web-search"))
    androidTestImplementation(project(":plugins:interaction"))
    androidTestImplementation(project(":plugins:filesystem"))
    androidTestImplementation(project(":plugins:llm:openai"))
    androidTestImplementation(project(":plugins:llm:azure-openai"))
    androidTestImplementation(project(":plugins:llm:anthropic"))
    androidTestImplementation(project(":plugins:llm:google"))
    androidTestImplementation(project(":plugins:llm:deepseek"))
    androidTestImplementation(project(":plugins:llm:openrouter"))
    androidTestImplementation(project(":plugins:llm:bedrock"))
    androidTestImplementation(project(":plugins:llm:mistral"))
    androidTestImplementation(project(":plugins:llm:alibaba"))
    androidTestImplementation(project(":plugins:llm:ollama"))
    androidTestImplementation(project(":plugins:llm:glm"))
    androidTestImplementation(project(":plugins:goal"))
    androidTestImplementation(project(":plugins:schedule"))
    androidTestImplementation(project(":plugins:ui-pages"))
    androidTestImplementation(project(":plugins:ui-messages"))
    androidTestImplementation(project(":plugins:ui-theme"))
    androidTestImplementation(project(":plugins:ui-profiles"))
    androidTestImplementation(project(":plugins:profile-recovery-ui"))
    androidTestImplementation(project(":plugins:ui-shell"))
    androidTestImplementation(project(":plugins:ui-contributions"))
    androidTestImplementation(project(":plugins:default-ui-bridge"))

    api(project(":plugins:api"))
    api(project(":plugins:runtime"))
    androidTestImplementation(project(":plugins:skills"))
    implementation(project(":plugins:installation-store"))
    implementation(project(":plugins:package-provider"))
    androidTestImplementation(project(":plugins:history-repository"))
    androidTestImplementation(project(":plugins:settings"))
    androidTestImplementation(project(":plugins:capability-providers"))

    androidTestImplementation(project(":plugins:native-notifications"))
    compileOnly(project(":plugins:web-search"))
    compileOnly(project(":plugins:conversation-overlay"))
    androidTestImplementation(project(":plugins:conversation-overlay"))
    implementation(libs.cordis.hmr)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(project(":plugins:application"))
    androidTestImplementation(project(":plugins:conversation-execution"))
    androidTestImplementation(project(":plugins:native-tool-approvals"))
    androidTestImplementation("com.tencent:mmkv-kmp:2.4.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

android.sourceSets.getByName("main").assets.srcDir(rootProject.layout.buildDirectory.dir("bundled"))
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn(":stageBundledPlugins")
}
