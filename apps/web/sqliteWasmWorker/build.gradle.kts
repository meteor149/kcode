@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

kotlin {
    wasmJs {
        browser()
        useEsModules()
    }
    sourceSets {
        commonMain.dependencies {
            api("androidx.sqlite:sqlite-web:2.7.0")
            implementation(
                npm(
                    "kcode-sqlite-wasm-worker",
                    layout.projectDirectory.dir("worker").asFile,
                ),
            )
        }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
        }
    }
}
