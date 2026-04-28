package com.ayakix.pocketradar.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentificationDecoderTest {

    @Test
    fun `pyModeS textbook sample decodes to KLM1023`() {
        val frame = AdsbFrame("8D4840D6202CC371C32CE0576098".hexToByteArray())
        assertEquals("KLM1023", IdentificationDecoder.decodeCallsign(frame))
    }

    @Test
    fun `decodes captured Delta callsign`() {
        // *8da7450525101332e74820fb9059;
        val frame = AdsbFrame("8da7450525101332e74820fb9059".hexToByteArray())
        assertEquals("DAL294", IdentificationDecoder.decodeCallsign(frame))
    }

    @Test
    fun `decodes captured ANA callsign`() {
        // *8d845daa2304e071c70d60de4551; — All Nippon Airways flight 1105
        val frame = AdsbFrame("8d845daa2304e071c70d60de4551".hexToByteArray())
        assertEquals("ANA1105", IdentificationDecoder.decodeCallsign(frame))
    }

    @Test
    fun `decodes captured PTA callsign`() {
        val frame = AdsbFrame("8d71c70925414075c34820b7d309".hexToByteArray())
        assertEquals("PTA504", IdentificationDecoder.decodeCallsign(frame))
    }

    @Test
    fun `rejects non-identification frames`() {
        // TC=11 (Airborne Position).
        val positionFrame = AdsbFrame("8d71c7095875d77f9e8a4aa03e8d".hexToByteArray())
        assertFailsWith<IllegalArgumentException> {
            IdentificationDecoder.decodeCallsign(positionFrame)
        }
    }
}
