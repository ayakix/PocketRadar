package com.ayakix.pocketradar.decoder

/**
 * Result of [inspectModeS]: the bare facts you can extract from a Mode S
 * frame without consuming it (no aggregation, no state change). Intended
 * for diagnostic / debug-log UIs that want to render the raw stream.
 */
data class ModeSInspection(
    val hex: String,
    val downlinkFormat: Int,
    /**
     * 24-bit ICAO address when the frame format carries it explicitly
     * (DF=11, 17, 18). For surveillance replies (DF=0/4/5) the ICAO is
     * XOR-folded into the CRC and would need address-recovery; we leave
     * it as `null` rather than show a misleading value.
     */
    val icao: IcaoAddress?,
    /** Type Code (top 5 bits of ME) for DF=17/18, otherwise `null`. */
    val typeCode: Int?,
    /** True iff the embedded CRC-24 verifies cleanly. */
    val isValidCrc: Boolean,
    val length: Length,
) {
    enum class Length { SHORT, LONG }
}

/**
 * Parse a Mode S hex frame for display purposes only — no decoder state is
 * touched. Returns `null` if the input is not a recognisable Mode S frame
 * (wrong length, non-hex characters, etc.).
 *
 * Accepts either bare hex (`8d71c7095875…`) or the dump1090 wire form
 * (`*8d71c709…;`).
 */
fun inspectModeS(hex: String): ModeSInspection? {
    val cleaned = hex.trim().trim('*', ';')
    val raw = runCatching { cleaned.hexToByteArray() }.getOrNull() ?: return null
    val length = when (raw.size) {
        ModeSFrame.SHORT_BYTES -> ModeSInspection.Length.SHORT
        ModeSFrame.LONG_BYTES -> ModeSInspection.Length.LONG
        else -> return null
    }

    val df = (raw[0].toInt() and 0xFF) ushr 3
    val isValidCrc = Crc24.syndrome(raw) == 0

    val icao: IcaoAddress? = if (length == ModeSInspection.Length.LONG && (df == 17 || df == 18)) {
        IcaoAddress(
            ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF),
        )
    } else if (length == ModeSInspection.Length.SHORT && df == 11) {
        // DF=11 All-Call Reply also carries the ICAO in bytes 1..3.
        IcaoAddress(
            ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF),
        )
    } else {
        null
    }

    val typeCode = if (length == ModeSInspection.Length.LONG && (df == 17 || df == 18)) {
        (raw[4].toInt() and 0xFF) ushr 3
    } else {
        null
    }

    return ModeSInspection(
        hex = cleaned.lowercase(),
        downlinkFormat = df,
        icao = icao,
        typeCode = typeCode,
        isValidCrc = isValidCrc,
        length = length,
    )
}
