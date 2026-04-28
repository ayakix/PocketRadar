package com.ayakix.pocketradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayakix.pocketradar.PocketRadarApp
import com.ayakix.pocketradar.decoder.Aircraft
import com.ayakix.pocketradar.decoder.IcaoAddress
import com.ayakix.pocketradar.domain.AircraftStore
import com.ayakix.pocketradar.domain.LatLng
import com.ayakix.pocketradar.service.RadarForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RadarViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app = application as PocketRadarApp
    private val store: AircraftStore = app.aircraftStore

    val aircraft: StateFlow<Map<IcaoAddress, Aircraft>> = store.aircraft
    val trails: StateFlow<Map<IcaoAddress, List<LatLng>>> = store.trails

    /** Current foreground service state (which source is running, or none). */
    val sourceState: StateFlow<SourceState> = RadarForegroundService.sourceState

    /** Errors raised by the service. UI subscribes via `LaunchedEffect`. */
    val errors: SharedFlow<String> = app.errors

    init {
        // Periodically prune stale aircraft so the map de-clutters as planes
        // exit the receiving range. Lives with the ViewModel rather than the
        // service so the prune cadence keeps running even when no source is
        // active (e.g., user has stopped reception but still has the screen
        // open with the last snapshot).
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                store.pruneStale(System.currentTimeMillis())
            }
        }
    }

    fun startReplay() {
        RadarForegroundService.start(app, SourceMode.REPLAY)
    }

    fun startLive() {
        RadarForegroundService.start(app, SourceMode.LIVE)
    }

    fun stop() {
        RadarForegroundService.stop(app)
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RadarViewModel(application) as T
    }
}
