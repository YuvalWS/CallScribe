plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}

buildscript {
    dependencies {
        // AGP 9 has built-in Kotlin; pin the Kotlin Gradle plugin so it matches the compose/serialization plugins.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
