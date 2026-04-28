package com.ayakix.pocketradar.decoder

/**
 * Information extracted from a single Airborne Position message (TC 9..18 with
 * barometric altitude, or TC 20..22 with GNSS altitude).
 *
 * Position itself **cannot** be decoded from one message alone — CPR encoding splits
 * lat/lon into [CprFormat.EVEN] and [CprFormat.ODD] halves that must be combined.
 * Use [CprDecoder.decodeGlobal] with one even and one odd frame from the same
 * aircraft within ~10 seconds.
 */
data class AirbornePosition(
    val altitudeFeet: Int?,         // null if a non-Q-bit (Mode C / Gillham) encoding was used
    val cprFormat: CprFormat,
    val cprLatitude: Int,           // 17-bit raw CPR value
    val cprLongitude: Int,          // 17-bit raw CPR value
)

/**
 * CPR frame parity. Two frames with opposite parities are needed for a global
 * (reference-free) position fix.
 */
enum class CprFormat { EVEN, ODD }
