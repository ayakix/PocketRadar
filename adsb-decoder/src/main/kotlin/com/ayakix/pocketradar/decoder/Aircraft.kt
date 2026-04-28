package com.ayakix.pocketradar.decoder

/**
 * The decoder's public output type — one record per aircraft, accumulated as
 * messages arrive. Fields are nullable because identification, position, and
 * velocity arrive in separate ADS-B messages, so a fresh contact may have only
 * a subset filled in until more frames are received.
 */
data class Aircraft(
    val icao: IcaoAddress,
    val callsign: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeFeet: Int? = null,
    val groundSpeedKnots: Int? = null,
    val trackDegrees: Double? = null,
    val verticalRateFpm: Int? = null,
    val verticalRateSource: VerticalRateSource? = null,
    /** Wall-clock millisecond timestamp of the last message that updated this record. */
    val lastSeenMillis: Long = 0L,
)
