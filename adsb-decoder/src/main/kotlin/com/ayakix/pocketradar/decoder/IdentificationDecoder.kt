package com.ayakix.pocketradar.decoder

/**
 * Decodes the callsign from an ADS-B Aircraft Identification message (Type Code 1..4).
 *
 * ME layout (56 bits):
 *   bits  0..4    Type Code (TC, already exposed by [AdsbFrame.typeCode])
 *   bits  5..7    Wake Vortex / Aircraft Category subtype (not parsed here)
 *   bits  8..55   Eight callsign characters, 6 bits each (48 bits total)
 *
 * The 6-bit values are looked up against the ICAO 8.2.7.1 character set:
 *   1..26  -> 'A'..'Z'
 *   32     -> ' '
 *   48..57 -> '0'..'9'
 *   anything else is invalid padding and is dropped.
 */
object IdentificationDecoder {

    // Indexed by 6-bit code 0..63. '?' marks invalid codes which we filter out.
    private const val ICAO_CHARSET =
        "?ABCDEFGHIJKLMNOPQRSTUVWXYZ????? ???????????????0123456789??????"

    /**
     * Returns the callsign with trailing spaces trimmed.
     *
     * Throws [IllegalArgumentException] if [frame] is not an Identification message.
     */
    fun decodeCallsign(frame: AdsbFrame): String {
        require(frame.typeCode in 1..4) {
            "Identification messages have TC 1..4, got TC=${frame.typeCode}"
        }
        // Pack ME bytes 1..6 (frame bytes 5..10) into the low 48 bits of a Long.
        // Byte 4 only contributes its lower 3 bits (the WVC / category subfield),
        // which we deliberately skip — those bits are not part of the callsign.
        val raw = frame.raw
        var packed = 0L
        for (i in 5..10) {
            packed = (packed shl 8) or (raw[i].toLong() and 0xFF)
        }
        // Read 8 characters MSB-first, dropping invalid codes silently.
        val sb = StringBuilder(8)
        for (i in 7 downTo 0) {
            val code = ((packed ushr (i * 6)) and 0x3F).toInt()
            val ch = ICAO_CHARSET[code]
            if (ch != '?') sb.append(ch)
        }
        return sb.toString().trimEnd()
    }
}
