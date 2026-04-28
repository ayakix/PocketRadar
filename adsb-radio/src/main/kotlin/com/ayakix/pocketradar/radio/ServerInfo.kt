package com.ayakix.pocketradar.radio

/**
 * Information sent by an rtl_tcp server in its 12-byte hello header.
 */
data class ServerInfo(
    /** Numeric tuner type (see [tunerName] for the human-readable form). */
    val tunerType: Int,
    /** Number of distinct gain levels exposed by the tuner. */
    val gainStageCount: Int,
) {

    /** Human-readable tuner family. RTL-SDR Blog V4 reports R828D (6). */
    val tunerName: String = when (tunerType) {
        0 -> "Unknown"
        1 -> "E4000"
        2 -> "FC0012"
        3 -> "FC0013"
        4 -> "FC2580"
        5 -> "R820T"
        6 -> "R828D"
        else -> "Unknown ($tunerType)"
    }
}
