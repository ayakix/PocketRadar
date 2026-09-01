package com.ayakix.pocketradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayakix.pocketradar.PocketRadarApp
import com.ayakix.pocketradar.decoder.Aircraft
import com.ayakix.pocketradar.decoder.IcaoAddress
import com.ayakix.pocketradar.decoder.inspectModeS
import com.ayakix.pocketradar.domain.AircraftStore
import com.ayakix.pocketradar.domain.CoverageRecord
import com.ayakix.pocketradar.domain.CoverageTracker
import com.ayakix.pocketradar.domain.LatLng
import com.ayakix.pocketradar.domain.MessageLog
import com.ayakix.pocketradar.domain.MessageLogEntry
import com.ayakix.pocketradar.domain.MessageStats
import com.ayakix.pocketradar.domain.ReceiverPosition
import com.ayakix.pocketradar.domain.greatCircleDistanceKm
import com.ayakix.pocketradar.driver.SdrDriver
import com.ayakix.pocketradar.radio.DiagnosticsProgress
import com.ayakix.pocketradar.radio.RfDiagnostics
import com.ayakix.pocketradar.service.RadarForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class RadarViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app = application as PocketRadarApp
    private val store: AircraftStore = app.aircraftStore
    private val messageLog: MessageLog = app.messageLog
    private val coverage: CoverageTracker = app.coverageTracker

    val aircraft: StateFlow<Map<IcaoAddress, Aircraft>> = store.aircraft
    val trails: StateFlow<Map<IcaoAddress, List<LatLng>>> = store.trails

    /** Bounded log of recent Mode S frames, for the debug sheet. */
    val logEntries: StateFlow<List<MessageLogEntry>> = messageLog.entries
    val logStats: StateFlow<MessageStats> = messageLog.stats

    /** Current foreground service state (which source is running, or none). */
    val sourceState: StateFlow<SourceState> = RadarForegroundService.sourceState

    /** Errors raised by the service. UI subscribes via `LaunchedEffect`. */
    val errors: SharedFlow<String> = app.errors

    /** Where ranges are measured from. Falls back to the fixture position. */
    val receiverPosition: StateFlow<ReceiverPosition> = app.receiverPosition.position

    /** Farthest contact per bearing sector, accumulated over the session. */
    val coverageSectors: StateFlow<List<CoverageRecord?>> = coverage.sectors
    val farthestContact: StateFlow<CoverageRecord?> = coverage.farthest
    val coverageSectorCount: Int get() = coverage.sectorCount

    private val _diagnostics = MutableStateFlow<DiagnosticsState>(DiagnosticsState.Idle)

    /** Progress and result of the RF self-test. */
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    private var diagnosticsJob: Job? = null

    init {
        // Periodically prune stale aircraft so the map de-clutters as planes
        // exit the receiving range. Lives with the ViewModel rather than the
        // service so the prune cadence keeps running even when no source is
        // active (e.g., user has stopped reception but still has the screen
        // open with the last snapshot).
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                store.pruneStale(now)
                // Coverage is folded in on the same cadence rather than per
                // frame: a position only changes a few times a second, and
                // this keeps the receive path free of geo maths.
                val origin = receiverPosition.value.position
                store.aircraft.value.values.forEach { coverage.record(origin, it, now) }
            }
        }
    }

    /** Adopt the device's own position as the receiving station location. */
    fun refreshReceiverPosition() {
        app.receiverPosition.refresh()
    }

    /** Great-circle distance from the receiver to [target], in km. */
    fun distanceKmTo(target: LatLng): Double =
        greatCircleDistanceKm(receiverPosition.value.position, target)

    fun resetCoverage() {
        coverage.reset()
    }

    /**
     * Run the RF self-test. The dongle serves one client at a time, so any
     * running source is stopped first — otherwise the diagnostics connect
     * races the foreground service for the same socket.
     */
    fun startDiagnostics() {
        if (_diagnostics.value is DiagnosticsState.Running) return
        stop()
        diagnosticsJob?.cancel()
        _diagnostics.value = DiagnosticsState.Running("接続中", 0, 1)
        diagnosticsJob = viewModelScope.launch {
            RfDiagnostics(
                host = SdrDriver.HOST,
                isCrcValid = { hex -> isCrcValid(hex) },
            ).run()
                .catch { t ->
                    _diagnostics.value = DiagnosticsState.Failed(
                        t.message ?: t::class.java.simpleName,
                    )
                }
                .collect { progress ->
                    _diagnostics.value = when (progress) {
                        is DiagnosticsProgress.Step -> DiagnosticsState.Running(
                            progress.label,
                            progress.completed,
                            progress.total,
                        )
                        is DiagnosticsProgress.Done -> DiagnosticsState.Complete(progress.report)
                    }
                }
        }
    }

    fun cancelDiagnostics() {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        _diagnostics.value = DiagnosticsState.Idle
    }

    /**
     * CRC gate for the diagnostics counters. `:adsb-radio` deliberately keeps
     * no dependency on `:adsb-decoder`, so the check is injected from here —
     * through the same `inspectModeS` entry point the debug log uses, so both
     * views agree on what counts as a valid frame.
     */
    private fun isCrcValid(hex: String): Boolean =
        inspectModeS(hex)?.isValidCrc == true

    fun startReplay() {
        RadarForegroundService.start(app, SourceMode.REPLAY)
    }

    fun startLive() {
        RadarForegroundService.start(app, SourceMode.LIVE)
    }

    /**
     * Surface a driver-launch failure through the same channel the service
     * uses, so the UI has a single place to show reception problems.
     */
    fun reportError(message: String) {
        app.postError(message)
    }

    fun stop() {
        RadarForegroundService.stop(app)
    }

    fun resetLog() {
        viewModelScope.launch { messageLog.reset() }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RadarViewModel(application) as T
    }
}
