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
    sourceSets.getByName("androidTest").assets.srcDir(project(":distribution:packager").layout.buildDirectory.dir("packages"))
    sourceSets.getByName("androidTest").assets.srcDir(project(":distribution:packager").layout.buildDirectory.dir("bundled"))
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(":distribution:packager:packageMessageCodec")
    dependsOn(":distribution:packager:stageBundledPlugins")
}

val nativeExecutionTestAssets = layout.buildDirectory.dir("generated/nativeExecutionTestAssets")
val stageNativeExecutionTestApk = tasks.register<Copy>("stageNativeExecutionTestApk") {
    dependsOn(":distribution:native-execution-android:assembleDebug")
    from(rootProject.layout.buildDirectory.file("distribution/android/native-execution/outputs/apk/debug/native-execution-android-debug.apk"))
    into(nativeExecutionTestAssets)
    rename { "native-execution.apk" }
}
android.sourceSets.getByName("androidTest").assets.srcDir(nativeExecutionTestAssets)
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(stageNativeExecutionTestApk)
}

val stageNativeUbuntuTestPackage = tasks.register<Copy>("stageNativeUbuntuTestPackage") {
    dependsOn(":distribution:packager:packageNativeUbuntu")
    val packageFile = project(":distribution:packager").layout.buildDirectory.file("preparation/provider.shell.ubuntu-1.0.0.kplugin")
    from(packageFile) { rename { "native-ubuntu.kplugin" } }
    from(packageFile.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") }) {
        rename { "native-ubuntu.kplugin.sha256" }
    }
    into(nativeExecutionTestAssets)
}
val stageNativeSystemShellTestPackage = tasks.register<Copy>("stageNativeSystemShellTestPackage") {
    dependsOn(":distribution:packager:packageNativeSystemShell")
    val packageFile = project(":distribution:packager").layout.buildDirectory.file("preparation/provider.shell.platform-1.0.0.kplugin")
    from(packageFile) { rename { "native-system-shell.kplugin" } }
    from(packageFile.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") }) {
        rename { "native-system-shell.kplugin.sha256" }
    }
    into(nativeExecutionTestAssets)
}
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(stageNativeUbuntuTestPackage)
    dependsOn(stageNativeSystemShellTestPackage)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    androidTestImplementation(project(":plugins:test-support"))
    androidTestImplementation(project(":plugins:default-ui-api"))
    androidTestImplementation("org.jetbrains.compose.material3:material3:1.8.2")
    androidTestImplementation(project(":plugins:markdown"))
    androidTestImplementation(project(":plugins:agent-loop"))
    androidTestImplementation(project(":plugins:session-history"))
    androidTestImplementation(project(":plugins:message-codec"))
    androidTestImplementation(project(":plugins:model-settings"))
    androidTestImplementation(project(":plugins:execution-settings"))
    androidTestImplementation(project(":plugins:tools"))
    androidTestImplementation(project(":plugins:system-prompt"))
    androidTestImplementation(project(":plugins:continuations"))
    androidTestImplementation(project(":plugins:subagents"))
    androidTestImplementation(project(":plugins:localization"))
    androidTestImplementation(project(":plugins:conversation-export"))
    androidTestImplementation(project(":plugins:shell"))
    androidTestImplementation(project(":plugins:web-container"))
    androidTestImplementation(project(":plugins:web-search"))
    androidTestImplementation(project(":plugins:interaction-settings"))
    androidTestImplementation(project(":plugins:filesystem"))
    androidTestImplementation(project(":plugins:skill-tools"))
    androidTestImplementation(project(":plugins:artifact-tools"))
    androidTestImplementation(project(":plugins:llm"))
    androidTestImplementation(project(":plugins:llm-service"))
    androidTestImplementation(project(":plugins:goal"))
    androidTestImplementation(project(":plugins:schedule"))
    androidTestImplementation(project(":plugins:ui-pages"))
    androidTestImplementation(project(":plugins:ui-contributions"))
    androidTestImplementation(project(":plugins:default-ui-bridge"))

    api(project(":plugins:api"))
    api(project(":plugins:runtime"))
    androidTestImplementation(project(":plugins:skills"))
    implementation(project(":plugins:installation-store"))
    implementation(project(":plugins:package-provider"))
    androidTestImplementation(project(":plugins:history-repository"))
    androidTestImplementation(project(":plugins:settings-repository"))
    androidTestImplementation(project(":plugins:capability-providers"))
    androidTestImplementation(project(":plugins:native-execution"))
    androidTestImplementation(project(":plugins:native-filesystem"))
    androidTestImplementation(project(":plugins:artifact-repository"))

    androidTestImplementation(project(":plugins:native-notifications"))
    compileOnly(project(":plugins:web-container"))
    compileOnly(project(":plugins:web-search"))
    compileOnly(project(":plugins:conversation-overlay"))
    androidTestImplementation(project(":plugins:conversation-overlay"))
    implementation(libs.cordis.hmr)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(project(":plugins:application"))
    androidTestImplementation(project(":plugins:conversation-execution"))
    androidTestImplementation(project(":plugins:settings-commands"))
    androidTestImplementation(project(":plugins:interaction"))
    androidTestImplementation(project(":plugins:native-tool-approvals"))
    androidTestImplementation("com.tencent:mmkv-kmp:2.4.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

android.sourceSets.getByName("main").assets.srcDir(project(":distribution:packager").layout.buildDirectory.dir("bundled"))
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn(":distribution:packager:stageBundledPlugins")
}
