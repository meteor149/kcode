import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val configuredReleaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0")
val configuredMacReleaseVersion = configuredReleaseVersion.map { version ->
    val components = version.split('.')
    val major = components.getOrNull(0)?.toIntOrNull() ?: 0
    if (major > 0) {
        version
    } else {
        val minor = components.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = components.getOrNull(2)?.toIntOrNull() ?: 0
        "1.$minor.$patch"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":plugins:api"))
    implementation(project(":plugins:profile-recovery-ui"))
    implementation(project(":plugins:platform-desktop"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

compose.desktop {
    application {
        mainClass = "ai.meteor.kcode.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("jdk.httpserver", "java.net.http")
            packageName = "kcode"
            packageVersion = configuredReleaseVersion.get()
            description = "A calm, cross-platform AI workspace powered by Koog."
            vendor = "kcode"

            windows {
                iconFile.set(project.file("src/main/resources/kcode-icon.ico"))
            }
            macOS {
                iconFile.set(project.file("src/main/resources/kcode-icon.icns"))
                packageVersion = configuredMacReleaseVersion.get()
                dmgPackageVersion = configuredMacReleaseVersion.get()
            }
            linux {
                iconFile.set(project.file("src/main/resources/kcode-icon.png"))
            }
        }
    }
}
