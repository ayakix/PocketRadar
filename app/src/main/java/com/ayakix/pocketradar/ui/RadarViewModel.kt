package com.ayakix.pocketradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayakix.pocketradar.data.MockMessageSource
import com.ayakix.pocketradar.decoder.Aircraft
import com.ayakix.pocketradar.decoder.IcaoAddress
import com.ayakix.pocketradar.domain.AircraftStore
import com.ayakix.pocketradar.domain.LatLng
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RadarViewModel(
    private val source: MockMessageSource,
    private val store: AircraftStore = AircraftStore(),
) : ViewModel() {

    val aircraft: StateFlow<Map<IcaoAddress, Aircraft>> = store.aircraft
    val trails: StateFlow<Map<IcaoAddress, List<LatLng>>> = store.trails

    init {
        viewModelScope.launch {
            source.stream().collect { hex ->
                store.ingest(hex, System.currentTimeMillis())
            }
        }

        viewModelScope.launch {
            // Prune stale aircraft once per second so the map de-clutters as planes
            // exit the receiving range.
            while (true) {
                delay(1_000)
                store.pruneStale(System.currentTimeMillis())
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RadarViewModel(MockMessageSource(context.applicationContext)) as T
        }
    }
}
