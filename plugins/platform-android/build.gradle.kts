plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "ai.meteor.kcode.plugin.platform.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    api(project(":shared"))
    api(project(":plugins:runtime"))
    implementation(project(":plugins:tools"))
    implementation(project(":plugins:filesystem"))
    implementation(project(":plugins:shell"))
    implementation(project(":plugins:web-container"))
    implementation(project(":plugins:web-search"))
    implementation(project(":plugins:skill-tools"))
    implementation(project(":plugins:artifact-tools"))
    implementation(libs.cordis.hmr)
    implementation("ai.koog:agents-ext:1.1.1-beta")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
