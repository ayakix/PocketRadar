package com.ayakix.pocketradar.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdsbFrameTest {

    @Test
    fun `parses the canonical pyModeS sample`() {
        // ICAO 4840D6 (KLM), TC=4 (aircraft identification).
        val frame = AdsbFrame("8D4840D6202CC371C32CE0576098".hexToByteArray())

        assertEquals(5, frame.capability)
        assertEquals("4840D6", frame.icao.toString())
        assertEquals(4, frame.typeCode)
        assertEquals(7, frame.me.size)
        // ME starts with 0x20 (TC=4, character index, etc.) — full identification
        // parsing is covered later in IdentificationTest.
        assertEquals(0x20.toByte(), frame.me[0])
    }

    @Test
    fun `parses a captured airborne position frame`() {
        // *8d71c7095875d77f9e8a4aa03e8d; — byte 4 = 0x58 = 0101 1000.
        // Top 5 bits = 01011 = 11 → TC=11 (Airborne Position, baro alt).
        val frame = AdsbFrame("8d71c7095875d77f9e8a4aa03e8d".hexToByteArray())

        assertEquals("71C709", frame.icao.toString())
        assertEquals(11, frame.typeCode)
    }

    @Test
    fun `parses a captured airborne velocity frame`() {
        // *8d71c709990d620ab05c1c6efe5e; — byte 4 = 0x99 = 1001 1001.
        // Top 5 bits = 10011 = 19 → TC=19 (Airborne Velocity).
        val frame = AdsbFrame("8d71c709990d620ab05c1c6efe5e".hexToByteArray())

        assertEquals("71C709", frame.icao.toString())
        assertEquals(19, frame.typeCode)
    }

    @Test
    fun `rejects a long non-ADS-B frame such as DF=20 Comm-B`() {
        // 0xA0 = 1010 0000 → DF = 10100 = 20 (Comm-B Reply with altitude). Long frame
        // but not ADS-B, so AdsbFrame must reject it.
        val df20 = "A04840D6202CC371C32CE0576098".hexToByteArray()
        assertFailsWith<IllegalArgumentException> {
            AdsbFrame(df20)
        }
    }

    @Test
    fun `rejects a short frame`() {
        assertFailsWith<IllegalArgumentException> {
            AdsbFrame("5d86da38aa7c0b".hexToByteArray())
        }
    }
}
