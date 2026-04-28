package com.ayakix.pocketradar.decoder

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decodes an Airborne Velocity message (Mode S DF=17, Type Code = 19).
 *
 * 56-bit ME layout for ground-speed subtypes (1 = subsonic, 2 = supersonic):
 *   bit  0..4   TC = 19
 *   bit  5..7   Subtype (1..4)
 *   bit  8      Intent change flag      (ignored)
 *   bit  9      IFR capability          (ignored)
 *   bit 10..12  NACv                    (ignored)
 *   bit 13      East-West direction (0 = East, 1 = West)
 *   bit 14..23  East-West velocity (10 bits, value-1 = knots, 4× for subtype 2)
 *   bit 24      North-South direction (0 = North, 1 = South)
 *   bit 25..34  North-South velocity (10 bits, same scaling)
 *   bit 35      Vertical-rate source (0 = Geometric, 1 = Barometric)
 *   bit 36      Vertical-rate sign (0 = up, 1 = down)
 *   bit 37..45  Vertical rate (9 bits, value-1 = 64 fpm units)
 *   bit 46..55  GNSS-baro alt diff      (ignored)
 */
object AirborneVelocityDecoder {

    fun decode(frame: AdsbFrame): AirborneVelocity {
        require(frame.typeCode == 19) {
            "Airborne velocity uses TC=19, got TC=${frame.typeCode}"
        }
        val raw = frame.raw
        var me = 0L
        for (i in 4..10) {
            me = (me shl 8) or (raw[i].toLong() and 0xFF)
        }

        val subtype = ((me ushr 48) and 0x7L).toInt()
        require(subtype in 1..2) {
            "Phase 1 only supports ground-speed subtypes (1 or 2), got subtype=$subtype"
        }
        val multiplier = if (subtype == 2) 4 else 1

        val ewDir = ((me ushr 42) and 1L).toInt()
        val ewVRaw = ((me ushr 32) and 0x3FFL).toInt()
        val nsDir = ((me ushr 31) and 1L).toInt()
        val nsVRaw = ((me ushr 21) and 0x3FFL).toInt()

        // Spec: encoded value = velocity + 1, so subtract 1 to get the actual figure.
        // Direction bit flips the sign.
        val vEw = (ewVRaw - 1) * multiplier * if (ewDir == 1) -1 else 1
        val vNs = (nsVRaw - 1) * multiplier * if (nsDir == 1) -1 else 1

        val groundSpeed = sqrt((vEw.toDouble() * vEw) + (vNs.toDouble() * vNs)).roundToInt()
        var trackDeg = Math.toDegrees(atan2(vEw.toDouble(), vNs.toDouble()))
        if (trackDeg < 0.0) trackDeg += 360.0

        val vrSource = if (((me ushr 20) and 1L) == 1L) VerticalRateSource.BAROMETRIC
        else VerticalRateSource.GEOMETRIC
        val vrSign = ((me ushr 19) and 1L).toInt()
        val vrRaw = ((me ushr 10) and 0x1FFL).toInt()
        // Encoded value 0 means "no vertical rate information"; we still propagate 0 fpm.
        val vrMagnitude = if (vrRaw == 0) 0 else (vrRaw - 1) * 64
        val verticalRate = if (vrSign == 1) -vrMagnitude else vrMagnitude

        return AirborneVelocity(
            groundSpeedKnots = groundSpeed,
            trackDegrees = trackDeg,
            verticalRateFpm = verticalRate,
            verticalRateSource = vrSource,
        )
    }
}
