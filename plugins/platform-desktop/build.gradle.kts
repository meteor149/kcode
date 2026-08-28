plugins {
    kotlin("jvm")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
