plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":plugins:api"))
            api("io.github.meteor149:include:0.0.1-SNAPSHOT")
            api(libs.cordis.packages)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmMain by creating { dependsOn(commonMain.get()) }
        getByName("desktopMain").dependsOn(jvmMain)
        getByName("androidMain").dependsOn(jvmMain)
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.profiles"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val bundlePackCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

tasks.register<JavaExec>("packProfileBundle") {
    group = "distribution"
    description = "Build a native Profile Bundle archive from a JSON request"
    dependsOn(bundlePackCompilation.compileTaskProvider)
    classpath(bundlePackCompilation.output.allOutputs, bundlePackCompilation.runtimeDependencyFiles)
    mainClass.set("ai.meteor.kcode.plugin.profiles.ProfileBundlePackCliKt")
    doFirst { args(providers.gradleProperty("profileBundleRequest").get()) }
}
