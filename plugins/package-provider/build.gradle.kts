import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

plugins {
    kotlin("multiplatform")
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
            api(libs.cordis.packages)
            compileOnly(libs.cordis.loader)
            compileOnly(libs.cordis.hmr)
            compileOnly(project(":plugins:default-ui-api"))
            compileOnly(project(":libraries:ui"))
        }
        val jvmAndroidMain by creating { dependsOn(commonMain.get()) }
        getByName("desktopMain").dependsOn(jvmAndroidMain)
        getByName("androidMain").dependsOn(jvmAndroidMain)
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("desktopTest").dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

// The SDK fingerprint is generated from actual shared compile artifacts, never a publisher
// assertion or a mutable SNAPSHOT version string. Export this descriptor alongside the SDK.
fun generatePackageAbi(platform: String, configurationName: String, sourceSetName: String) {
    val classpath = providers.provider {
        configurations.getByName(configurationName).incoming.artifactView {
            attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        }.files
    }
    val exportsFile = project(":plugins:api").file("src/commonMain/kotlin/ai/meteor/kcode/platform/PluginHostApiPackages.kt")
    val generated = layout.buildDirectory.dir("generated/packageAbi/$platform")
    val task = tasks.register("generate${platform.replaceFirstChar { it.uppercase() }}PackageAbi") {
        dependsOn(classpath)
        inputs.files(classpath).withPropertyName("sharedCompileArtifacts")
        inputs.file(exportsFile)
        outputs.dir(generated)
        doLast {
            val exports = Regex("(?m)^\\s*\"([^\"]+)\"").findAll(exportsFile.readText()).map { it.groupValues[1].replace("\\$", "$") }.toList().sorted()
            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            fun shared(name: String): Boolean = exports.any { prefix ->
                val path = prefix.replace('.', '/')
                name.startsWith("$path/") || name == "$path.class" || name.startsWith("$path$")
            }
            val artifacts = classpath.get().files.mapNotNull { file ->
                val contents = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.map { it.relativeTo(file).invariantSeparatorsPath to it.readBytes() }.toList()
                else if (file.extension in setOf("jar", "zip")) ZipFile(file).use { zip ->
                    zip.entries().asSequence().filterNot { it.isDirectory }.map { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }.toList()
                } else emptyList()
                if (contents.none { shared(it.first) }) null
                else hash(contents.sortedBy { it.first }.joinToString("\n") { "${it.first}=${hash(it.second)}" }.encodeToByteArray())
            }.sorted()
            check(artifacts.isNotEmpty()) { "No host-shared SDK artifacts resolved" }
            val descriptor = "cordis-host-sdk-v1\nplatform=$platform\nkotlin=2.3.21\ncompose=1.8.2\nentry=cordis-object-or-noarg\n" +
                exports.joinToString("\n", postfix = "\n") + artifacts.joinToString("\n", postfix = "\n")
            val output = generated.get().asFile.also { it.mkdirs() }
            output.resolve("sdk-abi-$platform.txt").writeText(descriptor)
            output.resolve("KcodePackageAbi.kt").writeText("package ai.meteor.kcode.plugin.packages\n\ninternal actual val NativePackageRuntimeAbi: String = \"${hash(descriptor.encodeToByteArray())}\"\n")
        }
    }
    kotlin.sourceSets.getByName(sourceSetName).kotlin.srcDir(task.map { generated.get() })
}

generatePackageAbi("desktop", "desktopCompileClasspath", "desktopMain")
generatePackageAbi("android", "debugCompileClasspath", "androidMain")

android {
    namespace = "ai.meteor.kcode.plugin.packages"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
