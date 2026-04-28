package com.ayakix.pocketradar.decoder

/**
 * A raw Mode S downlink frame. Only the first 5 bits (Downlink Format) are decoded
 * here; DF-specific decoding happens in the per-format classes such as [AdsbFrame].
 *
 * Mode S frames come in two sizes:
 *   - 56 bits (7 bytes, "short")  used by surveillance / All-Call replies (DF 0/4/5/11).
 *   - 112 bits (14 bytes, "long") used by Comm-B and ADS-B Extended Squitter (DF 16-21).
 */
class ModeSFrame(val raw: ByteArray) {

    init {
        require(raw.size == SHORT_BYTES || raw.size == LONG_BYTES) {
            "Mode S frames must be $SHORT_BYTES or $LONG_BYTES bytes, got ${raw.size}"
        }
    }

    /** Downlink Format. The top 5 bits of byte 0. ADS-B uses DF=17. */
    val downlinkFormat: Int = (raw[0].toInt() and 0xFF) ushr 3

    /** True for 112-bit frames (DF 16, 17, 18, 19, 20, 21, 24). */
    val isLong: Boolean get() = raw.size == LONG_BYTES

    companion object {
        const val SHORT_BYTES = 7
        const val LONG_BYTES = 14
    }
}
