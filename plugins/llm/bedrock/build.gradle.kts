plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.serialization")
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    sourceSets {
        commonMain.dependencies {
            api(project(":plugins:api"))
            api(project(":plugins:llm:adapter-support"))
        }
        getByName("desktopMain").dependencies {
            implementation("ai.koog:prompt-executor-bedrock-client:1.1.1")
            implementation("ai.koog:prompt-executor-anthropic-client:1.1.1")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "ai.meteor.kcode.plugin.llm.bedrock"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

extra["modelLabelsPackage"] = "ai.meteor.kcode.plugin.llm.labels.bedrock"
apply(from = rootProject.file("gradle/model-labels.gradle.kts"))
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(layout.buildDirectory.dir("generated/modelLabels/kotlin"))
