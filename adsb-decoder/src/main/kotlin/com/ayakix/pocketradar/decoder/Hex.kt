package com.ayakix.pocketradar.decoder

/**
 * Convert a hex string (e.g. "8D4840D6...") to its byte representation.
 *
 * Mode S messages travel as bytes on the air, but every reference and dump1090's
 * `--raw` output uses hex, so we need a fast round-trip.
 */
internal fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string length must be even, got $length" }
    val result = ByteArray(length / 2)
    for (i in result.indices) {
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        require(hi >= 0 && lo >= 0) {
            "Invalid hex character at offset ${2 * i} in '$this'"
        }
        result[i] = ((hi shl 4) or lo).toByte()
    }
    return result
}
