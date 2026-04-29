package com.ayakix.pocketradar.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModeSInspectionTest {

    @Test
    fun `inspects the canonical pyModeS sample as a valid DF-17 identification`() {
        val ins = inspectModeS("8D4840D6202CC371C32CE0576098")

        assertEquals(17, ins?.downlinkFormat)
        assertEquals("4840D6", ins?.icao?.toString())
        assertEquals(4, ins?.typeCode)
        assertTrue(ins?.isValidCrc == true)
        assertEquals(ModeSInspection.Length.LONG, ins?.length)
    }

    @Test
    fun `inspects a short DF-11 frame from the captured fixture`() {
        // From sample_messages.txt — DF=11 All-Call Reply, ICAO 86DA38.
        val ins = inspectModeS("5d86da38aa7c0b")

        assertEquals(11, ins?.downlinkFormat)
        assertEquals("86DA38", ins?.icao?.toString())
        assertNull(ins?.typeCode)  // Type Code lives in DF=17 / 18 only.
        assertTrue(ins?.isValidCrc == true)
        assertEquals(ModeSInspection.Length.SHORT, ins?.length)
    }

    @Test
    fun `accepts the dump1090 wire format with leading asterisk and trailing semicolon`() {
        val bare = inspectModeS("8D4840D6202CC371C32CE0576098")
        val wrapped = inspectModeS("*8D4840D6202CC371C32CE0576098;")
        assertEquals(bare, wrapped)
    }

    @Test
    fun `flags a corrupted frame as CRC invalid`() {
        // Flip a payload byte from the canonical sample.
        val ins = inspectModeS("8D4840D6FF2CC371C32CE0576098")
        assertEquals(17, ins?.downlinkFormat)
        // ICAO is still extracted from bytes 1..3 — CRC failure does not
        // make the address suddenly disappear; that's the point of having
        // a separate isValidCrc flag.
        assertEquals("4840D6", ins?.icao?.toString())
        assertFalse(ins?.isValidCrc == true)
    }

    @Test
    fun `returns null on nonsense input`() {
        assertNull(inspectModeS(""))
        assertNull(inspectModeS("not hex"))
        assertNull(inspectModeS("8D"))           // too short
        assertNull(inspectModeS("8D4840D6202C")) // odd length, neither 14 nor 28
    }
}
