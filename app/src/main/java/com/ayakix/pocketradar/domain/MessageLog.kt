package com.ayakix.pocketradar.domain

import com.ayakix.pocketradar.decoder.ModeSInspection
import com.ayakix.pocketradar.decoder.inspectModeS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One row in the debug log: a single Mode S frame as observed by the
 * receiver, plus its arrival timestamp.
 */
data class MessageLogEntry(
    val timestampMillis: Long,
    val inspection: ModeSInspection,
)

/**
 * Aggregate counters maintained alongside the log so the UI can show a
 * one-line summary (total received, CRC pass rate, unique aircraft, DF
 * distribution) without re-walking the entry list on every recomposition.
 */
data class MessageStats(
    val totalReceived: Int = 0,
    val crcValid: Int = 0,
    val uniqueIcaos: Int = 0,
    val byDownlinkFormat: Map<Int, Int> = emptyMap(),
)

/**
 * Bounded ring buffer of recent Mode S frames, kept process-wide so the
 * foreground service (the writer) and the debug sheet (the reader) share
 * a single view. Holds at most [maxEntries] entries; older ones fall off.
 *
 * The log is a strict superset of what `AircraftStore` retains: every frame
 * the decoder sees lands here, including ones the decoder rejects (wrong
 * DF, bad CRC, …). That makes it the right surface for a "what is the
 * antenna actually picking up" debug view.
 */
class MessageLog(
    private val maxEntries: Int = 200,
) {

    private val _entries = MutableStateFlow<List<MessageLogEntry>>(emptyList())
    val entries: StateFlow<List<MessageLogEntry>> = _entries.asStateFlow()

    private val _stats = MutableStateFlow(MessageStats())
    val stats: StateFlow<MessageStats> = _stats.asStateFlow()

    private val mutex = Mutex()
    private val seenIcaos = mutableSetOf<String>()

    /**
     * Append one frame to the log. Silently drops anything that doesn't
     * even parse as Mode S (wrong length, non-hex, etc.) — those would be
     * noise in the debug view, not signal.
     */
    suspend fun record(hex: String, timestampMillis: Long) {
        val inspection = inspectModeS(hex) ?: return
        mutex.withLock {
            val entry = MessageLogEntry(timestampMillis, inspection)
            _entries.update { (listOf(entry) + it).take(maxEntries) }

            inspection.icao?.toString()?.let { seenIcaos.add(it) }
            _stats.update { current ->
                val nextDf = current.byDownlinkFormat.toMutableMap()
                nextDf[inspection.downlinkFormat] =
                    (nextDf[inspection.downlinkFormat] ?: 0) + 1
                current.copy(
                    totalReceived = current.totalReceived + 1,
                    crcValid = current.crcValid + if (inspection.isValidCrc) 1 else 0,
                    uniqueIcaos = seenIcaos.size,
                    byDownlinkFormat = nextDf,
                )
            }
        }
    }

    /** Clear the log and reset the counters. */
    suspend fun reset() {
        mutex.withLock {
            _entries.value = emptyList()
            _stats.value = MessageStats()
            seenIcaos.clear()
        }
    }
}
