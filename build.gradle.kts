// Root build.gradle.kts
buildscript {
    dependencies {
        // Blueprint tools telling Gradle how to handle Android and Kotlin
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

// Simple cleanup task that clears out previous temporary build artifacts
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}