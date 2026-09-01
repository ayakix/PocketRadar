package com.ayakix.pocketradar.domain

import com.ayakix.pocketradar.radio.DiagnosticsReport
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serialises everything the debug sheet knows into one JSON document, meant
 * to be shared out of the app and read by a human or an analysis tool.
 *
 * JSON rather than a prettier text report because the primary consumer is
 * *analysis*, not reading on the phone: raw hex frames survive intact, and
 * the numbers keep full precision instead of display rounding.
 *
 * Uses `org.json` (bundled with Android) so the export path adds no
 * dependency to the APK.
 */
object DebugReport {

    fun build(
        appVersion: String,
        receiver: ReceiverPosition,
        stats: MessageStats,
        entries: List<MessageLogEntry>,
        coverageSectors: List<CoverageRecord?>,
        farthest: CoverageRecord?,
        diagnostics: DiagnosticsReport?,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("app_version", appVersion)
        root.put("exported_at", isoTimestamp(System.currentTimeMillis()))

        root.put("receiver", JSONObject().apply {
            put("latitude", receiver.position.latitude)
            put("longitude", receiver.position.longitude)
            put("height_m", receiver.heightMetres)
            put("source", receiver.source.name)
        })

        root.put("stats", JSONObject().apply {
            put("total_received", stats.totalReceived)
            put("crc_valid", stats.crcValid)
            put("unique_icaos", stats.uniqueIcaos)
            put("by_downlink_format", JSONObject().apply {
                stats.byDownlinkFormat.toSortedMap().forEach { (df, count) ->
                    put(df.toString(), count)
                }
            })
        })

        root.put("frames", JSONArray().apply {
            // Log entries arrive newest-first; export oldest-first so the
            // document reads as a chronological capture.
            entries.asReversed().forEach { entry ->
                put(JSONObject().apply {
                    put("t", isoTimestamp(entry.timestampMillis))
                    put("hex", entry.inspection.hex)
                    put("df", entry.inspection.downlinkFormat)
                    entry.inspection.typeCode?.let { put("tc", it) }
                    put("crc_ok", entry.inspection.isValidCrc)
                    entry.inspection.icao?.let { put("icao", it.toString()) }
                })
            }
        })

        root.put("coverage", JSONObject().apply {
            put("sector_width_deg", 360.0 / coverageSectors.size.coerceAtLeast(1))
            farthest?.let { put("farthest", coverageRecordJson(it)) }
            put("sectors", JSONArray().apply {
                coverageSectors.forEachIndexed { index, record ->
                    if (record != null) {
                        put(coverageRecordJson(record).put("sector", index))
                    }
                }
            })
        })

        diagnostics?.let { root.put("rf_diagnostics", diagnosticsJson(it)) }

        return root.toString(2)
    }

    private fun coverageRecordJson(record: CoverageRecord): JSONObject = JSONObject().apply {
        put("distance_km", record.distanceKm)
        put("bearing_deg", record.bearingDegrees)
        put("icao", record.icao)
        record.callsign?.let { put("callsign", it) }
        record.altitudeFeet?.let { put("altitude_ft", it) }
        put("t", isoTimestamp(record.timestampMillis))
    }

    private fun diagnosticsJson(report: DiagnosticsReport): JSONObject = JSONObject().apply {
        put("tuner", report.tunerName)

        put("bands", JSONArray().apply {
            report.bands.forEach { band ->
                put(JSONObject().apply {
                    put("label", band.target.label)
                    put("frequency_hz", band.target.frequencyHz)
                    put("role", band.target.role.name)
                    band.target.harmonicOfInterestHz?.let { put("harmonic_of_interest_hz", it) }
                    put("mean_dbfs", band.metrics.meanLevelDbfs)
                    put("peak_dbfs", band.metrics.peakLevelDbfs)
                    put("clip_rate", band.metrics.clipRate)
                    put("dc_offset_i", band.metrics.dcOffsetI)
                    put("dc_offset_q", band.metrics.dcOffsetQ)
                    put("samples", band.metrics.sampleCount)
                })
            }
        })

        put("gain_sweep", JSONArray().apply {
            report.gains.forEach { gain ->
                put(JSONObject().apply {
                    put("gain_db", gain.gainDb)
                    put("crc_valid_frames", gain.crcValidFrames)
                    put("frames_decoded", gain.framesDecoded)
                    put("preamble_matches", gain.preambleMatches)
                    put("window_ms", gain.windowMillis)
                    put("mean_dbfs", gain.metrics.meanLevelDbfs)
                    put("peak_dbfs", gain.metrics.peakLevelDbfs)
                    put("clip_rate", gain.metrics.clipRate)
                })
            }
        })

        put("findings", JSONArray().apply {
            report.findings.forEach { finding ->
                put(JSONObject().apply {
                    put("severity", finding.severity.name)
                    put("title", finding.title)
                    put("detail", finding.detail)
                })
            }
        })
    }

    private fun isoTimestamp(millis: Long): String = TimestampFormat.get().format(Date(millis))

    /** SimpleDateFormat is not thread-safe; give each thread its own. */
    private val TimestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
    }

    // v2: stats.by_downlink_format and stats.unique_icaos now count
    // CRC-valid frames only.
    private const val FORMAT_VERSION = 2
}
