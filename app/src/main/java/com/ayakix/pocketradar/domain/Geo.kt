package com.ayakix.pocketradar.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius (km), WGS84. */
private const val EarthRadiusKm = 6371.0088

/**
 * Great-circle distance in km between two positions (haversine).
 *
 * Haversine rather than the simpler equirectangular approximation because
 * ADS-B reception spans hundreds of kilometres, where the flat-earth error
 * grows past the precision we want to quote for a maximum-range record.
 */
fun greatCircleDistanceKm(from: LatLng, to: LatLng): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)

    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EarthRadiusKm * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Initial bearing from [from] to [to] in degrees, 0 = north, clockwise. */
fun initialBearingDegrees(from: LatLng, to: LatLng): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)

    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Radio horizon in km between an antenna at [observerHeightM] and a target at
 * [targetHeightM], both in metres above ground.
 *
 * Uses the standard 4/3-Earth-radius model, which folds normal atmospheric
 * refraction into an effective radius so the path can be treated as straight:
 *
 *     d ≈ 4.12 × (√h₁ + √h₂)
 *
 * 1090 MHz propagates quasi-optically, so this — not transmit power — is what
 * bounds ADS-B reception in practice. Comparing it against the measured
 * maximum range tells you whether the site is horizon-limited (as good as it
 * can get) or obstruction-limited (worth moving).
 */
fun radioHorizonKm(observerHeightM: Double, targetHeightM: Double): Double =
    4.12 * (sqrt(observerHeightM.coerceAtLeast(0.0)) + sqrt(targetHeightM.coerceAtLeast(0.0)))

/** Feet to metres, for turning a reported barometric altitude into a height. */
fun Int.feetToMetres(): Double = this * 0.3048
