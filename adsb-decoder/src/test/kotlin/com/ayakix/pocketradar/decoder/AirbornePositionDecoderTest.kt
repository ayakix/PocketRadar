package com.ayakix.pocketradar.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AirbornePositionDecoderTest {

    @Test
    fun `decodes captured even-frame position fields`() {
        // *8d71c7095875e3e5a95127f79925; — TC=11, F=EVEN, ALT=22550 ft
        val frame = AdsbFrame("8d71c7095875e3e5a95127f79925".hexToByteArray())
        val pos = AirbornePositionDecoder.decode(frame)

        assertEquals(22550, pos.altitudeFeet)
        assertEquals(CprFormat.EVEN, pos.cprFormat)
        assertEquals(127700, pos.cprLatitude)
        assertEquals(86311, pos.cprLongitude)
    }

    @Test
    fun `decodes captured odd-frame position fields`() {
        // *8d71c7095875d77f9e8a4aa03e8d; — TC=11, F=ODD, ALT=22525 ft
        val frame = AdsbFrame("8d71c7095875d77f9e8a4aa03e8d".hexToByteArray())
        val pos = AirbornePositionDecoder.decode(frame)

        assertEquals(22525, pos.altitudeFeet)
        assertEquals(CprFormat.ODD, pos.cprFormat)
        assertEquals(114639, pos.cprLatitude)
        assertEquals(35402, pos.cprLongitude)
    }

    @Test
    fun `rejects non-position frames`() {
        // TC=4 (Identification).
        val idFrame = AdsbFrame("8D4840D6202CC371C32CE0576098".hexToByteArray())
        assertFailsWith<IllegalArgumentException> {
            AirbornePositionDecoder.decode(idFrame)
        }
    }
}
