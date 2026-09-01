package com.ayakix.pocketradar.domain

import com.ayakix.pocketradar.decoder.Aircraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The farthest aircraft heard in one bearing sector. */
data class CoverageRecord(
    val distanceKm: Double,
    val bearingDegrees: Double,
    val icao: String,
    val callsign: String?,
    val altitudeFeet: Int?,
    val timestampMillis: Long,
)

/**
 * Per-bearing record of how far the receiver has actually heard, accumulated
 * over a session.
 *
 * Maximum range alone is a single lucky sample; split by bearing it becomes a
 * map of the site. A sector that falls short of its neighbours is being
 * blocked by terrain or a building, which is exactly the thing you want to
 * know before deciding whether to move the antenna — and it is information
 * the live map cannot show, because aircraft come and go.
 *
 * Records only accumulate; they are never pruned when an aircraft goes stale.
 * The point is the best the site has ever achieved, not what it hears now.
 */
class CoverageTracker(
    /** Number of equal bearing sectors. 36 → one per 10°. */
    val sectorCount: Int = 36,
) {

    private val _sectors = MutableStateFlow<List<CoverageRecord?>>(List(sectorCount) { null })

    /** Farthest contact per sector, index 0 = north, increasing clockwise. */
    val sectors: StateFlow<List<CoverageRecord?>> = _sectors.asStateFlow()

    private val _farthest = MutableStateFlow<CoverageRecord?>(null)

    /** Single farthest contact across all bearings. */
    val farthest: StateFlow<CoverageRecord?> = _farthest.asStateFlow()

    /** Degrees spanned by one sector. */
    val sectorWidthDegrees: Double get() = 360.0 / sectorCount

    /**
     * Fold one aircraft position into the record. Aircraft without a decoded
     * position are ignored — CPR needs an even/odd frame pair before it can
     * produce one, so early contacts legitimately have none yet.
     */
    fun record(receiver: LatLng, aircraft: Aircraft, timestampMillis: Long) {
        val lat = aircraft.latitude ?: return
        val lon = aircraft.longitude ?: return
        val position = LatLng(lat, lon)

        val distance = greatCircleDistanceKm(receiver, position)
        val bearing = initialBearingDegrees(receiver, position)
        val index = ((bearing / sectorWidthDegrees).toInt()) % sectorCount

        val candidate = CoverageRecord(
            distanceKm = distance,
            bearingDegrees = bearing,
            icao = aircraft.icao.toString(),
            callsign = aircraft.callsign,
            altitudeFeet = aircraft.altitudeFeet,
            timestampMillis = timestampMillis,
        )

        _sectors.update(index) { existing ->
            if (existing == null || distance > existing.distanceKm) candidate else existing
        }
        if ((_farthest.value?.distanceKm ?: -1.0) < distance) {
            _farthest.value = candidate
        }
    }

    fun reset() {
        _sectors.value = List(sectorCount) { null }
        _farthest.value = null
    }

    private fun MutableStateFlow<List<CoverageRecord?>>.update(
        index: Int,
        transform: (CoverageRecord?) -> CoverageRecord?,
    ) {
        value = value.toMutableList().also { it[index] = transform(it[index]) }
    }
}
