package com.ayakix.pocketradar.decoder

/**
 * Stateful aggregator that turns a stream of dump1090 `--raw`-style hex messages
 * into [Aircraft] snapshots. Tracks each aircraft by its ICAO address and merges
 * fields as new messages arrive.
 *
 * Phase 1 scope:
 *   - in-memory only, single-threaded, no networking
 *   - DF=17 ADS-B messages only (everything else is silently dropped)
 *   - CPR position requires a recent even/odd pair (no reference-position fallback)
 *
 * Phase 2 will wrap this class in a coroutine Flow so the UI can observe updates.
 */
class AdsbDecoder(
    /** Maximum age difference (ms) of an even/odd CPR pair to be combinable. */
    private val cprPairTtlMillis: Long = 10_000L,
    /** Aircraft are dropped from snapshots after this much silence (ms). */
    private val staleAfterMillis: Long = 60_000L,
) {

    private val states = mutableMapOf<IcaoAddress, MutableState>()

    /**
     * Ingest a single Mode S message. Accepts either bare hex
     * (`8d71c7095875d77f9e8a4aa03e8d`) or the dump1090 wire form (`*…;`).
     *
     * @return the updated aircraft if this was a recognised, CRC-clean ADS-B
     *   frame, or `null` otherwise (wrong size, non-DF=17, CRC mismatch, etc.).
     */
    fun ingest(hex: String, timestampMillis: Long): Aircraft? {
        val cleaned = hex.trim().trim('*', ';')
        val raw = runCatching { cleaned.hexToByteArray() }.getOrNull() ?: return null
        if (raw.size != ModeSFrame.LONG_BYTES) return null

        val df = (raw[0].toInt() and 0xFF) ushr 3
        if (df != AdsbFrame.ADSB_DF) return null
        if (Crc24.syndrome(raw) != 0) return null

        val frame = AdsbFrame(raw)
        val state = states.getOrPut(frame.icao) { MutableState(frame.icao) }
        state.lastSeenMillis = timestampMillis

        when (frame.typeCode) {
            in 1..4 -> state.callsign = IdentificationDecoder.decodeCallsign(frame)
            in 9..18 -> updatePosition(state, frame, timestampMillis)
            19 -> AirborneVelocityDecoder.decode(frame)?.let { state.velocity = it }
            // Other TCs (5..8 surface, 20..22 GNSS-alt position, 23..31 status)
            // are intentionally ignored for Phase 1.
        }
        return state.toAircraft()
    }

    /** Snapshot all currently-tracked aircraft, dropping anyone idle past the TTL. */
    fun snapshot(nowMillis: Long): List<Aircraft> {
        val cutoff = nowMillis - staleAfterMillis
        return states.values
            .filter { it.lastSeenMillis >= cutoff }
            .map { it.toAircraft() }
    }

    private fun updatePosition(state: MutableState, frame: AdsbFrame, timestamp: Long) {
        val pos = AirbornePositionDecoder.decode(frame)
        pos.altitudeFeet?.let { state.altitudeFeet = it }
        when (pos.cprFormat) {
            CprFormat.EVEN -> {
                state.evenPosition = pos
                state.evenTimestamp = timestamp
            }
            CprFormat.ODD -> {
                state.oddPosition = pos
                state.oddTimestamp = timestamp
            }
        }
        val even = state.evenPosition
        val odd = state.oddPosition
        if (even != null && odd != null) {
            val ageDiff = kotlin.math.abs(state.evenTimestamp - state.oddTimestamp)
            if (ageDiff <= cprPairTtlMillis) {
                CprDecoder.decodeGlobal(
                    even = even,
                    odd = odd,
                    evenIsLatest = state.evenTimestamp >= state.oddTimestamp,
                )?.let { (lat, lon) ->
                    state.latitude = lat
                    state.longitude = lon
                }
            }
        }
    }

    private class MutableState(val icao: IcaoAddress) {
        var callsign: String? = null
        var altitudeFeet: Int? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var velocity: AirborneVelocity? = null
        var evenPosition: AirbornePosition? = null
        var oddPosition: AirbornePosition? = null
        var evenTimestamp: Long = 0L
        var oddTimestamp: Long = 0L
        var lastSeenMillis: Long = 0L

        fun toAircraft(): Aircraft = Aircraft(
            icao = icao,
            callsign = callsign,
            latitude = latitude,
            longitude = longitude,
            altitudeFeet = altitudeFeet,
            groundSpeedKnots = velocity?.groundSpeedKnots,
            trackDegrees = velocity?.trackDegrees,
            verticalRateFpm = velocity?.verticalRateFpm,
            verticalRateSource = velocity?.verticalRateSource,
            lastSeenMillis = lastSeenMillis,
        )
    }
}
