package com.ayakix.pocketradar.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Streams pre-recorded Mode S hex messages from `assets/captured_messages.txt`
 * at a roughly real-world rate (~50 ms apart). This lets the rest of the app be
 * exercised without an SDR dongle, and stays in place as the offline
 * fallback when the live `rtl_tcp` source is not connected.
 */
class MockMessageSource(
    private val context: Context,
    private val intervalMillis: Long = 50,
    private val assetName: String = "captured_messages.txt",
) {

    fun stream(): Flow<String> = flow {
        // Loop the fixture so the demo keeps producing aircraft for as long as
        // the collector is alive; the captured file is only ~150 s of replay.
        // Cancellation propagates through `delay`, so the inner stream is closed
        // promptly when the collector goes away.
        while (true) {
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isNotBlank()) {
                        emit(line)
                        delay(intervalMillis)
                    }
                }
            }
        }
    }
}
