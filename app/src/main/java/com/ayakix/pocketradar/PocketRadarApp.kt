package com.ayakix.pocketradar

import android.app.Application
import com.ayakix.pocketradar.domain.AircraftStore
import com.ayakix.pocketradar.domain.MessageLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide singletons that survive Activity recreation but die with the
 * process. Holding the [AircraftStore] and [MessageLog] here lets the
 * `RadarForegroundService` (which owns the receive coroutine) and the
 * `RadarViewModel` (which renders the map and the debug sheet) share a
 * single source of truth.
 *
 * Errors raised by the service (e.g., rtl_tcp connection failures) are
 * forwarded through [errors] so any visible Activity can surface them as a
 * toast.
 */
class PocketRadarApp : Application() {

    val messageLog: MessageLog by lazy { MessageLog() }
    val aircraftStore: AircraftStore by lazy { AircraftStore(messageLog = messageLog) }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** Called from the foreground service to surface a user-visible error. */
    fun postError(message: String) {
        _errors.tryEmit(message)
    }
}
