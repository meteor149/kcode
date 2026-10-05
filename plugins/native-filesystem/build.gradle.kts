plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

// This release carries its private provider adapter; SDK/framework identities stay in the host.
tasks.register<Jar>("packagedDesktopJar") {
    dependsOn("desktopJar", ":plugins:capability-providers:desktopJar")
    archiveClassifier.set("packaged-desktop")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from({ zipTree((tasks.getByName("desktopJar") as Jar).archiveFile.get().asFile) })
    from({ zipTree((project(":plugins:capability-providers").tasks.getByName("desktopJar") as Jar).archiveFile.get().asFile) })
    exclude("META-INF/MANIFEST.MF")
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
            implementation(project(":plugins:capability-providers"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidUnitTest.dependencies { implementation("ai.koog:agents-ext:1.1.1-beta") }
    }
}
android {
    namespace = "ai.meteor.kcode.plugin.nativefilesystem"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
