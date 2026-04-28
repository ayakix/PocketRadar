package com.ayakix.pocketradar.decoder

/**
 * Velocity information from an Airborne Velocity message (TC=19).
 *
 * The library supports the ground-speed subtypes (1 and 2) which cover all
 * civil subsonic and supersonic traffic. Subtypes 3 and 4 (airspeed-with-
 * heading) are left unsupported — they yield a heading angle rather than a
 * track, so the calling code would need to handle them differently anyway.
 */
data class AirborneVelocity(
    val groundSpeedKnots: Int,
    val trackDegrees: Double,         // true track over the ground, 0..360
    val verticalRateFpm: Int,         // positive = climbing
    val verticalRateSource: VerticalRateSource,
)

enum class VerticalRateSource { BAROMETRIC, GEOMETRIC }
