package com.ayakix.pocketradar.decoder

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Compact Position Reporting decoder.
 *
 * CPR is the encoding ADS-B uses to fit lat/lon into 17 bits each. A single message
 * is ambiguous because it only carries the position relative to a "zone", so the
 * decoder needs **either**:
 *   - a recent even/odd frame pair (globally unambiguous), implemented here, or
 *   - a known reference position (locally unambiguous), out of scope for Phase 1.
 *
 * Algorithm follows ICAO 9871 / DO-260B and matches the reference implementation in
 * Junzi Sun's *The 1090 Megahertz Riddle*.
 */
object CprDecoder {

    private const val CPR_DENOMINATOR = 131072.0  // 2^17

    /**
     * Decode lat/lon from a pair of CPR frames (one even, one odd) emitted by the
     * same aircraft within ~10 seconds.
     *
     * @param evenIsLatest pass `true` if [even] arrived after [odd] (its time tag
     *   is the more recent one), `false` if [odd] is the latest. The decoder picks
     *   which of the two latitude/longitude estimates to return based on this flag.
     * @return a (lat, lon) pair in degrees, or `null` if the two frames straddle
     *   a longitude-zone boundary (NL_even ≠ NL_odd) and cannot be combined.
     */
    fun decodeGlobal(
        even: AirbornePosition,
        odd: AirbornePosition,
        evenIsLatest: Boolean,
    ): Pair<Double, Double>? {
        require(even.cprFormat == CprFormat.EVEN) { "First argument must be the even frame" }
        require(odd.cprFormat == CprFormat.ODD) { "Second argument must be the odd frame" }

        val latCprE = even.cprLatitude / CPR_DENOMINATOR
        val lonCprE = even.cprLongitude / CPR_DENOMINATOR
        val latCprO = odd.cprLatitude / CPR_DENOMINATOR
        val lonCprO = odd.cprLongitude / CPR_DENOMINATOR

        // ---- Latitude ----
        // j is the "latitude zone index" derived from both frames.
        val j = floor(59.0 * latCprE - 60.0 * latCprO + 0.5).toInt()

        val dLatEven = 360.0 / 60.0
        val dLatOdd = 360.0 / 59.0

        var latEven = dLatEven * (j.mod(60) + latCprE)
        var latOdd = dLatOdd * (j.mod(59) + latCprO)

        // Latitudes wrap: anything above 270° belongs to the southern hemisphere.
        if (latEven >= 270.0) latEven -= 360.0
        if (latOdd >= 270.0) latOdd -= 360.0

        // The two latitude estimates must fall in the same NL zone — otherwise the
        // longitude reconstruction below would mix two different zoning schemes.
        if (numberOfLongitudeZones(latEven) != numberOfLongitudeZones(latOdd)) return null

        val lat = if (evenIsLatest) latEven else latOdd

        // ---- Longitude ----
        val nl = numberOfLongitudeZones(lat)
        val nEven = max(nl, 1)
        val nOdd = max(nl - 1, 1)

        val m = floor(lonCprE * (nl - 1) - lonCprO * nl + 0.5).toInt()

        var lon = if (evenIsLatest) {
            (360.0 / nEven) * (m.mod(nEven) + lonCprE)
        } else {
            (360.0 / nOdd) * (m.mod(nOdd) + lonCprO)
        }
        if (lon >= 180.0) lon -= 360.0  // normalise to [-180, 180)

        return lat to lon
    }

    /**
     * Number of longitude zones (1..59) used by CPR at the given latitude. Lookup
     * table from ICAO Annex 10 / DO-260B Table A-21. The table is symmetric in
     * north/south, so we work with `|lat|`.
     */
    fun numberOfLongitudeZones(latDeg: Double): Int {
        val absLat = abs(latDeg)
        for (i in NL_THRESHOLDS.indices) {
            if (absLat < NL_THRESHOLDS[i]) return 59 - i
        }
        return 1
    }

    private val NL_THRESHOLDS = doubleArrayOf(
        10.47047130, 14.82817437, 18.18626357, 21.02939493, 23.54504487,
        25.82924707, 27.93898710, 29.91135686, 31.77209708, 33.53993436,
        35.22899598, 36.85025108, 38.41241892, 39.92256684, 41.38651832,
        42.80914012, 44.19454951, 45.54626723, 46.86733252, 48.16039128,
        49.42776439, 50.67150166, 51.89342469, 53.09516153, 54.27817472,
        55.44378444, 56.59318756, 57.72747354, 58.84763776, 59.95459277,
        61.04917774, 62.13216659, 63.20427479, 64.26616523, 65.31845310,
        66.36171008, 67.39646774, 68.42322022, 69.44242631, 70.45451075,
        71.45986473, 72.45884545, 73.45177442, 74.43893416, 75.42056257,
        76.39684391, 77.36789461, 78.33374083, 79.29428225, 80.24923213,
        81.19801349, 82.13956981, 83.07199445, 83.99173563, 84.89166191,
        85.75541621, 86.53536998, 87.00000000,
    )
}
