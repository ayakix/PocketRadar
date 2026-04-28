package com.ayakix.pocketradar.ui

import android.content.Context
import com.ayakix.pocketradar.data.MockMessageSource
import com.ayakix.pocketradar.radio.RtlTcpMessageSource
import kotlinx.coroutines.flow.Flow

/**
 * Common abstraction over the two message sources `:app` can collect from:
 * `MockMessageSource` (Phase 2 fixture replay) and `RtlTcpMessageSource`
 * (Phase 3B live `rtl_tcp`). Both already expose `fun stream(): Flow<String>`,
 * so we just adapt them through a `fun interface`.
 */
fun interface MessageSource {
    fun stream(): Flow<String>
}

/** Which source the foreground service should run. */
enum class SourceMode { REPLAY, LIVE }

fun String.toMode(): SourceMode = SourceMode.valueOf(this)

fun SourceMode.toMessageSource(context: Context): MessageSource = when (this) {
    SourceMode.REPLAY -> {
        val mock = MockMessageSource(context.applicationContext)
        MessageSource { mock.stream() }
    }
    SourceMode.LIVE -> {
        val rtl = RtlTcpMessageSource()
        MessageSource { rtl.stream() }
    }
}

/** Snapshot of what the foreground service is currently doing. */
data class SourceState(val mode: SourceMode, val running: Boolean)
