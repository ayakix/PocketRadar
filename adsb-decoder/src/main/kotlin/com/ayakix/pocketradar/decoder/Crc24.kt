package com.ayakix.pocketradar.decoder

/**
 * Mode S CRC-24 used to verify the integrity of every Mode S downlink frame, including
 * ADS-B (DF=17) messages.
 *
 * The generator polynomial is x^24 + x^23 + x^22 + ... + 1, encoded as 0xFFF409
 * (the top x^24 bit is implied by the 24-bit register width). For a clean frame, running
 * this CRC over all 112 bits leaves a zero syndrome.
 */
internal object Crc24 {

    private const val POLY: Int = 0xFFF409
    private const val MASK_24: Int = 0xFFFFFF

    /**
     * Computes the CRC-24 syndrome over the given Mode S frame.
     *
     * Written bit-by-bit on purpose so the polynomial-division structure stays visible.
     * A table-driven version would be ~24x faster but hides the mechanics.
     *
     * @param frame 14 bytes (long, 112-bit) or 7 bytes (short, 56-bit) Mode S frame.
     * @return 24-bit syndrome. 0 means the frame's CRC is consistent.
     */
    fun syndrome(frame: ByteArray): Int {
        var register = 0
        for (byte in frame) {
            for (bitPosition in 7 downTo 0) {
                val msgBit = (byte.toInt() ushr bitPosition) and 1
                val topBit = (register ushr 23) and 1
                register = ((register shl 1) or msgBit) and MASK_24
                if (topBit == 1) {
                    register = register xor POLY
                }
            }
        }
        return register
    }
}
