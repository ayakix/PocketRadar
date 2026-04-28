package com.ayakix.pocketradar.decoder

/**
 * Extracts altitude and CPR-encoded lat/lon from an Airborne Position message.
 *
 * 56-bit ME layout (DO-260 §2.2.3.2.3):
 *   bit  0..4   Type Code (9..18 baro alt, 20..22 GNSS alt)
 *   bit  5..6   Surveillance Status
 *   bit  7      Single Antenna Flag
 *   bit  8..19  Altitude (12 bits — Q-bit encoding handled below)
 *   bit  20     Time
 *   bit  21     CPR Format (0 = EVEN, 1 = ODD)
 *   bit  22..38 CPR Latitude (17 bits)
 *   bit  39..55 CPR Longitude (17 bits)
 */
object AirbornePositionDecoder {

    fun decode(frame: AdsbFrame): AirbornePosition {
        require(frame.typeCode in 9..18 || frame.typeCode in 20..22) {
            "Airborne position uses TC 9..18 or 20..22, got TC=${frame.typeCode}"
        }
        // Pack the 56-bit ME (frame bytes 4..10) into the low 56 bits of a Long.
        val raw = frame.raw
        var me = 0L
        for (i in 4..10) {
            me = (me shl 8) or (raw[i].toLong() and 0xFF)
        }

        // Altitude: 12 bits at ME[8..19]. ME bit i lives at Long bit (55 - i).
        val alt12 = ((me ushr (55 - 19)) and 0xFFF).toInt()
        val altitudeFeet = decodeAltitude12(alt12)

        // CPR Format: ME bit 21.
        val cprFormat = if (((me ushr (55 - 21)) and 1L) == 0L) CprFormat.EVEN else CprFormat.ODD

        // CPR Latitude: 17 bits at ME[22..38].
        val cprLat = ((me ushr (55 - 38)) and 0x1FFFF).toInt()

        // CPR Longitude: 17 bits at ME[39..55].
        val cprLon = (me and 0x1FFFF).toInt()

        return AirbornePosition(
            altitudeFeet = altitudeFeet,
            cprFormat = cprFormat,
            cprLatitude = cprLat,
            cprLongitude = cprLon,
        )
    }

    /**
     * Decode the 12-bit altitude field. The 8th bit from the MSB (= bit index 4 from
     * the LSB inside [alt12]) is the Q-bit:
     *   Q=1 → 25-foot resolution: drop the Q-bit, treat the remaining 11 bits as N,
     *         altitude = N * 25 - 1000 [feet].
     *   Q=0 → Gillham (Mode C) encoded 100-foot resolution. We don't decode it here
     *         and return null (these are rare on modern transponders).
     */
    private fun decodeAltitude12(alt12: Int): Int? {
        val qBit = (alt12 ushr 4) and 1
        if (qBit == 0) return null
        val high7 = (alt12 ushr 5) and 0x7F  // bits 11..5
        val low4 = alt12 and 0xF             // bits  3..0
        val n = (high7 shl 4) or low4        // 11-bit value with the Q-bit removed
        return n * 25 - 1000
    }
}
