plugins {
    kotlin("jvm")
}

group = "com.ayakix.pocketradar"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Test-only: use the :adsb-decoder library to verify which demodulated
    // frames are genuine (CRC passes). The production module stays free of
    // any :adsb-decoder dependency — see adsb-radio/README.md.
    testImplementation(project(":adsb-decoder"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // The IqDemodulator fixture is ~23 MB of raw I/Q; magnitudes triple that
    // in memory. Give the test JVM enough headroom.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
