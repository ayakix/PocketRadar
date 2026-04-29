package com.ayakix.pocketradar.domain

import com.ayakix.pocketradar.decoder.AdsbDecoder
import com.ayakix.pocketradar.decoder.Aircraft
import com.ayakix.pocketradar.decoder.IcaoAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps [AdsbDecoder] and exposes a reactive snapshot of currently-tracked
 * aircraft, plus a per-aircraft trail of recent positions for drawing the
 * flight path on the map.
 *
 * Both [ingest] and [pruneStale] mutate two `StateFlow`s in sequence. They run
 * from different coroutines (message stream vs. periodic prune), so the pair
 * of updates is wrapped in a [Mutex] to keep the aircraft map and the trail
 * map in lockstep — without this guard, a prune cycle interleaved between
 * `_aircraft` and `_trails` updates could erase a freshly added trail point.
 */
class AircraftStore(
    private val decoder: AdsbDecoder = AdsbDecoder(),
    private val maxTrailPoints: Int = 100,
    /**
     * Optional debug log. When present, every hex frame the store sees is
     * also recorded for the debug UI — including frames the decoder
     * rejects (wrong DF, CRC mismatch, etc.).
     */
    private val messageLog: MessageLog? = null,
) {

    private val _aircraft = MutableStateFlow<Map<IcaoAddress, Aircraft>>(emptyMap())
    val aircraft: StateFlow<Map<IcaoAddress, Aircraft>> = _aircraft.asStateFlow()

    private val _trails = MutableStateFlow<Map<IcaoAddress, List<LatLng>>>(emptyMap())
    val trails: StateFlow<Map<IcaoAddress, List<LatLng>>> = _trails.asStateFlow()

    private val mutex = Mutex()

    /** Push one Mode S hex message into the decoder. */
    suspend fun ingest(hex: String, timestampMillis: Long) {
        // Always record for the debug log first — the user wants to see
        // even the frames the decoder rejects.
        messageLog?.record(hex, timestampMillis)

        val updated = decoder.ingest(hex, timestampMillis) ?: return

        mutex.withLock {
            _aircraft.update { it + (updated.icao to updated) }

            val lat = updated.latitude
            val lon = updated.longitude
            if (lat != null && lon != null) {
                _trails.update { current ->
                    val existing = current[updated.icao] ?: emptyList()
                    val nextPoint = LatLng(lat, lon)
                    // Avoid duplicate consecutive points (e.g. when only altitude updates).
                    val trail = if (existing.lastOrNull() == nextPoint) existing
                    else (existing + nextPoint).takeLast(maxTrailPoints)
                    current + (updated.icao to trail)
                }
            }
        }
    }

    /**
     * Drop aircraft that have not been heard recently. Snapshot pulls the live
     * decoder state; trails are pruned to match.
     */
    suspend fun pruneStale(nowMillis: Long) {
        mutex.withLock {
            val active = decoder.snapshot(nowMillis).associateBy { it.icao }
            _aircraft.value = active
            _trails.update { current -> current.filterKeys { it in active } }
        }
    }
}

/** Lightweight lat/lon pair. Kept domain-side to avoid pulling in Maps types here. */
data class LatLng(val latitude: Double, val longitude: Double)
