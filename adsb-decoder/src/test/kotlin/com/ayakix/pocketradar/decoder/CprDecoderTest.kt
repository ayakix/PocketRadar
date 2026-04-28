package com.ayakix.pocketradar.decoder

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CprDecoderTest {

    @Test
    fun `globally decodes a real Tokyo-area pair`() {
        // Even and odd position frames from the same aircraft (ICAO 71C709) captured
        // seconds apart. Expected position computed by pyModeS reference algorithm.
        val even = AirbornePositionDecoder.decode(
            AdsbFrame("8d71c7095875e3e5a95127f79925".hexToByteArray())
        )
        val odd = AirbornePositionDecoder.decode(
            AdsbFrame("8d71c7095875d77f9e8a4aa03e8d".hexToByteArray())
        )

        val pos = CprDecoder.decodeGlobal(even, odd, evenIsLatest = true)

        assertNotNull(pos)
        val (lat, lon) = pos
        // Roughly Tokyo Bay area.
        assertEquals(35.84564, lat, absoluteTolerance = 1e-4)
        assertEquals(139.93875, lon, absoluteTolerance = 1e-4)
    }

    @Test
    fun `picks the odd estimate when odd frame is the most recent`() {
        val even = AirbornePositionDecoder.decode(
            AdsbFrame("8d71c7095875e3e5a95127f79925".hexToByteArray())
        )
        val odd = AirbornePositionDecoder.decode(
            AdsbFrame("8d71c7095875d77f9e8a4aa03e8d".hexToByteArray())
        )

        val pos = CprDecoder.decodeGlobal(even, odd, evenIsLatest = false)

        assertNotNull(pos)
        // Slightly different from the even-latest result; both should land within ~50 m.
        assertEquals(35.84518, pos.first, absoluteTolerance = 1e-4)
        assertEquals(139.94116, pos.second, absoluteTolerance = 1e-4)
    }

    @Test
    fun `NL table matches well-known boundary values`() {
        // Spec sanity: NL(0) = 59, NL(87) = 1, NL just below 87 = 2, NL(45.5) = 38.
        assertEquals(59, CprDecoder.numberOfLongitudeZones(0.0))
        assertEquals(2, CprDecoder.numberOfLongitudeZones(86.9))
        assertEquals(1, CprDecoder.numberOfLongitudeZones(87.0))
        assertEquals(1, CprDecoder.numberOfLongitudeZones(89.0))
        // Symmetric in north/south.
        assertEquals(59, CprDecoder.numberOfLongitudeZones(-5.0))
    }
}

private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
    if (abs(expected - actual) > absoluteTolerance) {
        throw AssertionError("Expected $expected ± $absoluteTolerance but got $actual")
    }
}
