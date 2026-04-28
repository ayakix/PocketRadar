package com.ayakix.pocketradar.domain

import com.ayakix.pocketradar.decoder.AdsbDecoder
import com.ayakix.pocketradar.decoder.Aircraft
import com.ayakix.pocketradar.decoder.IcaoAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Wraps [AdsbDecoder] and exposes a reactive snapshot of currently-tracked
 * aircraft, plus a per-aircraft trail of recent positions for drawing the
 * flight path on the map.
 */
class AircraftStore(
    private val decoder: AdsbDecoder = AdsbDecoder(),
    private val maxTrailPoints: Int = 100,
) {

    private val _aircraft = MutableStateFlow<Map<IcaoAddress, Aircraft>>(emptyMap())
    val aircraft: StateFlow<Map<IcaoAddress, Aircraft>> = _aircraft.asStateFlow()

    private val _trails = MutableStateFlow<Map<IcaoAddress, List<LatLng>>>(emptyMap())
    val trails: StateFlow<Map<IcaoAddress, List<LatLng>>> = _trails.asStateFlow()

    /** Push one Mode S hex message into the decoder. */
    fun ingest(hex: String, timestampMillis: Long) {
        val updated = decoder.ingest(hex, timestampMillis) ?: return

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

    /**
     * Drop aircraft that have not been heard recently. Snapshot pulls the live
     * decoder state; trails are pruned to match.
     */
    fun pruneStale(nowMillis: Long) {
        val active = decoder.snapshot(nowMillis).associateBy { it.icao }
        _aircraft.value = active
        _trails.update { current -> current.filterKeys { it in active } }
    }
}

/** Lightweight lat/lon pair. Kept domain-side to avoid pulling in Maps types here. */
data class LatLng(val latitude: Double, val longitude: Double)
