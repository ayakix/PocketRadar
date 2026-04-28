package com.ayakix.pocketradar.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the bit-by-bit CRC-24 implementation against a textbook sample and a few
 * messages captured from a real RTL-SDR session (sample_messages.txt).
 */
class Crc24Test {

    @Test
    fun `syndrome is zero for the canonical pyModeS textbook sample`() {
        // DF=17 (ADS-B) identification of ICAO 4840D6 — featured throughout
        // Junzi Sun's "The 1090 Megahertz Riddle".
        val frame = "8D4840D6202CC371C32CE0576098".hexToByteArray()
        assertEquals(0, Crc24.syndrome(frame))
    }

    @Test
    fun `syndrome is non-zero when a payload bit is flipped`() {
        val original = "8D4840D6202CC371C32CE0576098".hexToByteArray()
        val corrupted = original.copyOf().also {
            // Flip the lowest bit of byte 5 (somewhere in the ME payload).
            it[5] = (it[5].toInt() xor 0x01).toByte()
        }
        assertNotEquals(0, Crc24.syndrome(corrupted))
    }

    @Test
    fun `syndrome is zero for DF=17 frames captured from dump1090`() {
        // Picked from src/test/resources/sample_messages.txt. These are real frames
        // whose CRC must verify, otherwise the receiver would not have emitted them.
        val samples = listOf(
            "8d71c7095875d77f9e8a4aa03e8d",
            "8d71c709990d620ab05c1c6efe5e",
            "8da745055843a3e6f15380a81a08",
            "8da74505ea21482ffd5c082b338d",
            "8d86da38990ca6804840055ab280",
        )
        for (hex in samples) {
            assertEquals(
                0,
                Crc24.syndrome(hex.hexToByteArray()),
                "Sample $hex should have CRC syndrome 0",
            )
        }
    }
}

