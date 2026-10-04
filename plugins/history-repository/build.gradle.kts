plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room3")
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
            implementation("androidx.room3:room3-runtime:3.0.1")
            implementation("androidx.sqlite:sqlite-bundled:2.7.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":plugins:test-support"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
        }
    }
}

dependencies {
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.1")
    add("kspDesktop", "androidx.room3:room3-compiler:3.0.1")
}
room3 { schemaDirectory(layout.projectDirectory.dir("schemas")) }
android {
    namespace = "ai.meteor.kcode.plugin.history"
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
