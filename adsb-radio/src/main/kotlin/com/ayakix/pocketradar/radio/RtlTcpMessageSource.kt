package com.ayakix.pocketradar.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Live source of Mode S hex frames backed by an `rtl_tcp` server.
 *
 * Composes [RtlTcpClient] (Phase 3A — I/Q over TCP) with [IqDemodulator]
 * (Phase 3B — I/Q → hex) to produce a `Flow<String>` of demodulated frames.
 * It is a drop-in replacement for the Phase 2 `MockMessageSource`, so `:app`
 * can switch between the captured-fixture replay and live reception just by
 * swapping the source instance.
 *
 * Lifecycle: each call to [stream] opens a fresh socket, applies the ADS-B
 * defaults, and demodulates samples until the collector cancels or the
 * server closes the connection. The socket is released via `RtlTcpClient.use`.
 *
 * Frame integrity: the demodulator is intentionally simple and emits many
 * false-positive frames; CRC verification (and therefore filtering) is the
 * consumer's responsibility — typically `AdsbDecoder` from `:adsb-decoder`.
 *
 * Errors: if [RtlTcpClient.connect] fails (server unreachable, wrong magic,
 * etc.) the exception propagates to the collector as a flow terminal error.
 * Callers should wrap the collection site with `.catch { ... }` or equivalent.
 */
class RtlTcpMessageSource(
    private val host: String = "localhost",
    private val port: Int = RtlTcpProtocol.DEFAULT_PORT,
    private val demodulator: IqDemodulator = IqDemodulator(),
) {

    fun stream(): Flow<String> = flow {
        RtlTcpClient(host, port).use { rtl ->
            rtl.connect()
            rtl.applyAdsbDefaults()

            // I/Q chunks arrive in ~16 KB bursts. We keep a rolling tail of
            // the previous chunk so frames that straddle a chunk boundary
            // still get detected. To avoid re-emitting frames that lie
            // entirely inside the tail (already detected last time), we only
            // emit frames whose preamble starts at sample offset >= tail size
            // (in post-decimation samples).
            var tail = ByteArray(0)
            rtl.samples().collect { chunk ->
                val combined = tail + chunk
                val tailOutputSamples = demodulator.outputSamplesFor(tail.size)
                demodulator.demodulate(combined).forEach { detected ->
                    if (detected.sampleOffset >= tailOutputSamples) {
                        emit(detected.hex)
                    }
                }
                tail = if (combined.size > TAIL_BYTES)
                    combined.copyOfRange(combined.size - TAIL_BYTES, combined.size)
                else combined
            }
        }
    }.flowOn(Dispatchers.Default)

    companion object {
        /**
         * Bytes carried over between consecutive I/Q chunks. One Mode S frame
         * spans at most 120 μs (8 μs preamble + 112 μs payload). At 2.4 MS/s
         * that's ~288 samples × 2 bytes = 576 bytes; round up to 1 KB for
         * margin against re-detection lag and decimation rounding.
         */
        private const val TAIL_BYTES = 1024
    }
}
