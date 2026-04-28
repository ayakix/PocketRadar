package com.ayakix.pocketradar.decoder

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AirborneVelocityDecoderTest {

    @Test
    fun `decodes captured climbing aircraft at 363 knots`() {
        // *8d71c709990d620ab05c1c6efe5e;
        val v = AirborneVelocityDecoder.decode(
            AdsbFrame("8d71c709990d620ab05c1c6efe5e".hexToByteArray())
        )
        assertNotNull(v)
        assertEquals(363, v.groundSpeedKnots)
        assertNear(283.39, v.trackDegrees, 0.01)
        assertEquals(1408, v.verticalRateFpm)
        assertEquals(VerticalRateSource.BAROMETRIC, v.verticalRateSource)
    }

    @Test
    fun `decodes captured descending light aircraft`() {
        // *8d86da38990ca6804840055ab280;
        val v = AirborneVelocityDecoder.decode(
            AdsbFrame("8d86da38990ca6804840055ab280".hexToByteArray())
        )
        assertNotNull(v)
        assertEquals(165, v.groundSpeedKnots)
        assertNear(269.65, v.trackDegrees, 0.01)
        assertEquals(-960, v.verticalRateFpm)
        assertEquals(VerticalRateSource.GEOMETRIC, v.verticalRateSource)
    }

    @Test
    fun `decodes captured north-bound climber`() {
        // *8da745059914132f108c1034f1d6;
        val v = AirborneVelocityDecoder.decode(
            AdsbFrame("8da745059914132f108c1034f1d6".hexToByteArray())
        )
        assertNotNull(v)
        assertEquals(375, v.groundSpeedKnots)
        assertNear(357.25, v.trackDegrees, 0.01)
        assertEquals(2176, v.verticalRateFpm)
    }

    @Test
    fun `rejects non-velocity frames`() {
        // TC=11 (Position).
        val pos = AdsbFrame("8d71c7095875d77f9e8a4aa03e8d".hexToByteArray())
        assertFailsWith<IllegalArgumentException> {
            AirborneVelocityDecoder.decode(pos)
        }
    }
}

private fun assertNear(expected: Double, actual: Double, tolerance: Double) {
    if (abs(expected - actual) > tolerance) {
        throw AssertionError("Expected $expected ± $tolerance but got $actual")
    }
}
