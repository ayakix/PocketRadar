package com.ayakix.pocketradar.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Streams pre-recorded Mode S hex messages from `assets/captured_messages.txt`
 * at a roughly real-world rate (~50 ms apart). This lets the rest of the app be
 * exercised without an SDR dongle, and stays in place as the Phase 2 fallback
 * when the real TCP source (Phase 3) is not connected.
 */
class MockMessageSource(
    private val context: Context,
    private val intervalMillis: Long = 50,
    private val assetName: String = "captured_messages.txt",
) {

    fun stream(): Flow<String> = flow {
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
