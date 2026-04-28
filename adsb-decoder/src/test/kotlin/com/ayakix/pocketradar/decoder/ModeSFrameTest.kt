package com.ayakix.pocketradar.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModeSFrameTest {

    @Test
    fun `extracts DF=17 from a long ADS-B frame`() {
        // 0x8D = 1000 1101 → top 5 bits = 10001 (17), bottom 3 bits = 101 (CA, decoded later).
        val frame = ModeSFrame("8D4840D6202CC371C32CE0576098".hexToByteArray())
        assertEquals(17, frame.downlinkFormat)
        assertTrue(frame.isLong)
    }

    @Test
    fun `extracts DF=11 from a short All-Call Reply`() {
        // *5d86da38aa7c0b; — 0x5D = 0101 1101 → DF = 01011 = 11.
        val frame = ModeSFrame("5d86da38aa7c0b".hexToByteArray())
        assertEquals(11, frame.downlinkFormat)
        assertFalse(frame.isLong)
    }

    @Test
    fun `extracts DF=0 from a short surveillance frame`() {
        // *028182bed588bf; — 0x02 = 0000 0010 → DF = 00000 = 0.
        val frame = ModeSFrame("028182bed588bf".hexToByteArray())
        assertEquals(0, frame.downlinkFormat)
    }

    @Test
    fun `rejects frames of unexpected size`() {
        assertFailsWith<IllegalArgumentException> {
            ModeSFrame(ByteArray(8))
        }
    }
}
