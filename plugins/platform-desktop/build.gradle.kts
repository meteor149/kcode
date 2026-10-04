plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
    // Native rendering belongs to the desktop host, rather than neutral shared contracts.
    runtimeOnly(compose.desktop.currentOs)
    testImplementation(project(":plugins:default-ui-api"))
    testImplementation("org.jetbrains.compose.material3:material3:1.8.2")
    testImplementation(project(":plugins:markdown"))
    testImplementation(project(":plugins:agent-loop"))
    testImplementation(project(":plugins:session-history"))
    testImplementation(project(":plugins:message-codec"))
    testImplementation(project(":plugins:model-settings"))
    testImplementation(project(":plugins:localization"))
    testImplementation(project(":plugins:conversation-export"))
    testImplementation(project(":plugins:shell"))
    testImplementation(project(":plugins:web-container"))
    testImplementation(project(":plugins:web-search-provider"))
    testImplementation(project(":plugins:web-search"))
    testImplementation(project(":plugins:filesystem"))
    testImplementation(project(":plugins:skill-tools"))
    testImplementation(project(":plugins:artifact-tools"))
    testImplementation(project(":plugins:llm"))
    testImplementation(project(":plugins:goal"))
    testImplementation(project(":plugins:schedule"))
    testImplementation(project(":plugins:schedule-dispatch"))
    api(project(":plugins:api"))
    api(project(":plugins:runtime"))
    implementation(project(":plugins:interaction"))
    implementation(project(":plugins:skills"))
    implementation(project(":plugins:installation-store"))
    implementation(project(":plugins:history-repository"))
    implementation(project(":plugins:settings-repository"))
    implementation(project(":plugins:capability-providers"))
    implementation(project(":plugins:filesystem"))
    implementation(project(":plugins:shell"))
    implementation(project(":plugins:native-execution"))
    implementation(project(":plugins:native-filesystem"))
    implementation(project(":plugins:artifact-repository"))
    implementation(project(":plugins:conversation-export"))
    implementation(project(":plugins:native-notifications"))
    implementation(project(":plugins:web-container"))
    implementation(project(":plugins:web-search-provider"))
    implementation(project(":plugins:web-search"))
    implementation(project(":plugins:skill-tools"))
    implementation(project(":plugins:artifact-tools"))
    implementation(libs.cordis.hmr)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation(project(":plugins:test-support"))
    testImplementation(project(":plugins:interaction"))
    testImplementation(project(":plugins:skills"))
    testImplementation(project(":plugins:tools"))
    testImplementation(project(":plugins:installation-store"))
    testImplementation("ai.koog:agents-ext:1.1.1-beta")
    testImplementation(project(":plugins:application"))
    testImplementation(project(":plugins:conversation-execution"))
    testImplementation(project(":plugins:conversation-overlay"))
    testImplementation(project(":plugins:ui-pages"))
    testImplementation(project(":plugins:ui-settings"))
    testImplementation(project(":plugins:subagent-provider"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    // External HTTP plugins carry their private network dependencies in the test artifact.
    systemProperty("kcode.private.search.classpath", configurations.testRuntimeClasspath.get().files
        .filter { it.name.startsWith("ktor-") || it.name.startsWith("slf4j-") }
        .joinToString(File.pathSeparator) { it.absolutePath })
}
