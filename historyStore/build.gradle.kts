plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room3")
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
    wasmJs { browser() }
    listOf(iosArm64(), iosSimulatorArm64())

    sourceSets {
        commonMain.dependencies {
            api("androidx.room3:room3-runtime:3.0.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        val nativeSqliteMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("androidx.sqlite:sqlite-bundled:2.7.0")
            }
        }
        getByName("androidMain").dependsOn(nativeSqliteMain)
        getByName("desktopMain").dependsOn(nativeSqliteMain)
        val iosMain by creating { dependsOn(nativeSqliteMain) }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("wasmJsMain").dependencies {
            implementation("androidx.sqlite:sqlite-web:2.7.0")
            implementation(project(":sqliteWasmWorker"))
        }
        getByName("commonTest").dependencies { implementation(kotlin("test")) }
    }
}

dependencies {
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.1")
    add("kspDesktop", "androidx.room3:room3-compiler:3.0.1")
    add("kspIosArm64", "androidx.room3:room3-compiler:3.0.1")
    add("kspIosSimulatorArm64", "androidx.room3:room3-compiler:3.0.1")
    add("kspWasmJs", "androidx.room3:room3-compiler:3.0.1")
}

room3 {
    schemaDirectory(layout.projectDirectory.dir("schemas"))
}

android {
    namespace = "app.kcode.history"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
