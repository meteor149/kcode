plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("android") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("com.android.application") version "8.10.0" apply false
    id("com.android.library") version "8.10.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
    id("androidx.room3") version "3.0.1" apply false
}
