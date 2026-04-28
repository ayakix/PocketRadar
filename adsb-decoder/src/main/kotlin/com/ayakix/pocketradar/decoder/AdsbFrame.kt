package com.ayakix.pocketradar.decoder

/**
 * A parsed ADS-B Extended Squitter (Mode S DF=17) frame.
 *
 * 112-bit layout:
 *   bits   0-4    Downlink Format = 17
 *   bits   5-7    Capability subfield (CA)
 *   bits   8-31   ICAO aircraft address (24 bits)
 *   bits  32-87   Message extended squitter payload (ME, 56 bits)
 *                   - first 5 bits of ME are the Type Code (TC), which selects the
 *                     subtype parser (identification / position / velocity / ...).
 *   bits  88-111  CRC-24 (already verified upstream).
 */
class AdsbFrame(val raw: ByteArray) {

    init {
        require(raw.size == ModeSFrame.LONG_BYTES) {
            "ADS-B frames are ${ModeSFrame.LONG_BYTES} bytes, got ${raw.size}"
        }
        val df = (raw[0].toInt() and 0xFF) ushr 3
        require(df == ADSB_DF) { "Not an ADS-B frame: DF=$df" }
    }

    /** Capability subfield (3 bits). */
    val capability: Int = raw[0].toInt() and 0x07

    /** 24-bit ICAO aircraft address (bytes 1..3). */
    val icao: IcaoAddress = IcaoAddress(
        ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            (raw[3].toInt() and 0xFF),
    )

    /** Type Code: top 5 bits of the ME payload (= top 5 bits of byte 4). */
    val typeCode: Int = (raw[4].toInt() and 0xFF) ushr 3

    /** 7-byte ME payload (bytes 4..10), inclusive of the Type Code bits. */
    val me: ByteArray get() = raw.copyOfRange(4, 11)

    companion object {
        const val ADSB_DF = 17
    }
}
