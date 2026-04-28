plugins {
    // Auto-download a matching JDK if the requested toolchain is not available locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "PocketRadar"

include(":adsb-decoder")
