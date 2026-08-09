plugins {
    kotlin("multiplatform") version "2.2.21-0.4.0"
    kotlin("plugin.serialization") version "2.2.21-0.4.0"
    id("org.jetbrains.compose") version "1.9.2-0.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21-0.4.0"
}

val repositoryRoot = layout.projectDirectory.dir("../../..")
val harmonyApp = layout.projectDirectory.dir("..").asFile

kotlin {
    ohosArm64 {
        binaries.sharedLib {
            baseName = "kn"
            export("org.jetbrains.compose.export:export:1.9.2-0.4.0")
            linkerOpts("-lz", "-lrcp_c")
        }
    }
    ohosX64 {
        binaries.sharedLib {
            baseName = "kn"
            export("org.jetbrains.compose.export:export:1.9.2-0.4.0")
            linkerOpts("-lz", "-lrcp_c")
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(repositoryRoot.dir("shared/src/commonMain/kotlin"))
            kotlin.srcDir(repositoryRoot.dir("extensions/webContainer/src/commonMain/kotlin"))
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.compose.ui:ui-backhandler:1.9.2-0.4.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-0.4.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.1-0.3.0")
            }
        }
        val ohosMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(repositoryRoot.dir("shared/src/ohosMain/kotlin"))
            dependencies {
                api("org.jetbrains.compose.export:export:1.9.2-0.4.0")
            }
        }
        getByName("ohosArm64Main").dependsOn(ohosMain)
        getByName("ohosX64Main").dependsOn(ohosMain)
    }
}

compose.resources {
    packageOfResClass = "kcode.shared.generated.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = provider {
            repositoryRoot.dir("shared/src/commonMain/composeResources")
        },
    )
}

val publishHarmonyComposeResources = tasks.register<Sync>("publishHarmonyComposeResources") {
    group = "harmony"
    dependsOn("ohosX64AggregateResources")
    from(layout.buildDirectory.dir("kotlin-multiplatform-resources/aggregated-resources/ohosX64/composeResources"))
    into(harmonyApp.resolve("entry/src/main/resources/rawfile/composeResources"))
}

arrayOf("debug", "release").forEach { type ->
    val buildType = type.replaceFirstChar { it.uppercase() }
    val publishArm64Header = tasks.register<Copy>("publish${buildType}HarmonyArm64Header") {
        group = "harmony"
        dependsOn("link${buildType}SharedOhosArm64")
        from(layout.buildDirectory.file("bin/ohosArm64/${type}Shared/libkn_api.h"))
        into(harmonyApp.resolve("entry/src/main/cpp/include/arm64-v8a"))
    }
    val publishArm64Library = tasks.register<Copy>("publish${buildType}HarmonyArm64Library") {
        group = "harmony"
        dependsOn("link${buildType}SharedOhosArm64")
        from(layout.buildDirectory.file("bin/ohosArm64/${type}Shared/libkn.so"))
        into(harmonyApp.resolve("entry/libs/arm64-v8a"))
    }
    val publishArm64 = tasks.register("publish${buildType}BinariesToHarmonyAppArm64") {
        group = "harmony"
        dependsOn(publishArm64Header, publishArm64Library, publishHarmonyComposeResources)
    }
    val publishX64Header = tasks.register<Copy>("publish${buildType}HarmonyX64Header") {
        group = "harmony"
        dependsOn("link${buildType}SharedOhosX64")
        from(layout.buildDirectory.file("bin/ohosX64/${type}Shared/libkn_api.h"))
        into(harmonyApp.resolve("entry/src/main/cpp/include/x86_64"))
    }
    val publishX64Library = tasks.register<Copy>("publish${buildType}HarmonyX64Library") {
        group = "harmony"
        dependsOn("link${buildType}SharedOhosX64")
        from(layout.buildDirectory.file("bin/ohosX64/${type}Shared/libkn.so"))
        into(harmonyApp.resolve("entry/libs/x86_64"))
    }
    val publishX64 = tasks.register("publish${buildType}BinariesToHarmonyAppX64") {
        group = "harmony"
        dependsOn(publishX64Header, publishX64Library, publishHarmonyComposeResources)
    }
    tasks.register("publish${buildType}BinariesToHarmonyApp") {
        group = "harmony"
        dependsOn(publishArm64, publishX64)
    }
}
