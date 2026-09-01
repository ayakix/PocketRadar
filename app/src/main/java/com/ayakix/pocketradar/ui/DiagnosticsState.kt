package com.ayakix.pocketradar.ui

import com.ayakix.pocketradar.radio.DiagnosticsReport

/** UI-facing state of the RF self-test. */
sealed interface DiagnosticsState {

    data object Idle : DiagnosticsState

    data class Running(
        val label: String,
        val completed: Int,
        val total: Int,
    ) : DiagnosticsState {
        val fraction: Float get() = if (total <= 0) 0f else completed.toFloat() / total
    }

    data class Complete(val report: DiagnosticsReport) : DiagnosticsState

    /**
     * The run could not finish. Almost always means the SDR driver was not
     * serving — the dongle is unplugged, permission was denied, or a live
     * session still holds the single available connection.
     */
    data class Failed(val reason: String) : DiagnosticsState
}
