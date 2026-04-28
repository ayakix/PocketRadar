plugins {
    kotlin("jvm")
}

group = "com.ayakix.pocketradar"
version = "0.1.0-SNAPSHOT"

// Repositories are declared centrally in settings.gradle.kts via
// dependencyResolutionManagement.

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
