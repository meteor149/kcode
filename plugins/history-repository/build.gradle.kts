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

// Room and its collection implementation are private to the repository generation.
// SQLite/JNI, Kotlin and coroutines retain the host SDK identity.
tasks.register<Jar>("packagedDesktopJar") {
    dependsOn("desktopJar")
    archiveClassifier.set("packaged-desktop")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from({ zipTree((tasks.getByName("desktopJar") as Jar).archiveFile.get().asFile) })
    from({
        val privateArtifacts = configurations.getByName("desktopRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.filter {
                it.moduleVersion.id.group == "androidx.room3" ||
                    it.moduleVersion.id.group == "androidx.collection"
            }
        check(privateArtifacts.size == 3) {
            "History package requires Room runtime/common and collection; found " +
                privateArtifacts.joinToString { it.moduleVersion.id.toString() }
        }
        privateArtifacts.sortedBy { it.moduleVersion.id.toString() }.map { zipTree(it.file) }
    })
    exclude("META-INF/MANIFEST.MF")
}

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
