package com.ayakix.pocketradar.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/** What a [BandTarget] is measured for. */
enum class BandRole {
    /** Known-empty spectrum near 1090 MHz; establishes the noise floor. */
    QUIET_REFERENCE,

    /** The ADS-B channel itself. */
    ADSB,

    /** A carrier we expect to find energy on, scanned to size up the site. */
    SURVEY,
}

/**
 * One frequency the band scan visits.
 *
 * @property harmonicOfInterestHz Set when this carrier's second harmonic
 *   lands on the ADS-B channel. A transmitter here does not have to be
 *   anywhere near 1090 MHz to ruin reception: if it drives the tuner's input
 *   stage into its nonlinear region, the stage generates the harmonic
 *   internally and it appears right on top of the wanted signal.
 */
data class BandTarget(
    val label: String,
    val frequencyHz: Int,
    val role: BandRole = BandRole.SURVEY,
    val note: String? = null,
    val harmonicOfInterestHz: Int? = null,
)

/** Level measurement at one [BandTarget]. */
data class BandResult(
    val target: BandTarget,
    val metrics: SignalMetrics,
)

/** Reception performance at one tuner gain setting. */
data class GainResult(
    val gainTenthsDb: Int,
    val metrics: SignalMetrics,
    val preambleMatches: Int,
    val framesDecoded: Int,
    val crcValidFrames: Int,
    val windowMillis: Long,
) {
    val gainDb: Double get() = gainTenthsDb / 10.0

    val validFramesPerSecond: Double
        get() = if (windowMillis <= 0) 0.0 else crcValidFrames * 1000.0 / windowMillis

    val preamblesPerSecond: Double
        get() = if (windowMillis <= 0) 0.0 else preambleMatches * 1000.0 / windowMillis

    /**
     * Share of decoded frames that pass CRC. A healthy receiver sits well
     * above zero; a detector firing on noise produces plenty of frames and
     * almost no valid ones.
     */
    val crcYield: Double
        get() = if (framesDecoded == 0) 0.0 else crcValidFrames.toDouble() / framesDecoded
}

enum class Severity { CRITICAL, WARNING, INFO, GOOD }

/** One conclusion drawn from the measurements. */
data class Finding(
    val severity: Severity,
    val title: String,
    val detail: String,
)

data class DiagnosticsReport(
    val tunerName: String,
    val bands: List<BandResult>,
    val gains: List<GainResult>,
    val findings: List<Finding>,
) {
    /** Gain that produced the most CRC-valid frames, or null if none did. */
    val bestGain: GainResult? get() = gains.maxByOrNull { it.crcValidFrames }.takeIf { it?.crcValidFrames ?: 0 > 0 }
}

/** Progress emitted while [RfDiagnostics.run] works through its sequence. */
sealed interface DiagnosticsProgress {
    data class Step(val label: String, val completed: Int, val total: Int) : DiagnosticsProgress
    data class Done(val report: DiagnosticsReport) : DiagnosticsProgress
}

/**
 * Scripted receiver self-test that runs entirely over the existing rtl_tcp
 * link — no extra hardware, no filter, no antenna change.
 *
 * The point is to separate failure causes that all look the same from the map
 * screen (no aircraft appear):
 *
 *  1. **Band scan** — retune the dongle across a handful of known carriers at
 *     one fixed gain and record the level at each. Strong energy far from
 *     1090 MHz is what overloads a filterless front end, and this is the only
 *     way to see it: at 1090 itself, an overloaded receiver and a quiet one
 *     can read almost the same.
 *  2. **Gain sweep** — at 1090 MHz, step the tuner gain from minimum to
 *     maximum and count what actually decodes at each step. The *shape* of
 *     that curve is the diagnosis. A healthy site improves with gain and then
 *     plateaus. An overloaded site peaks early and then collapses, because
 *     intermodulation products grow roughly 3 dB for every 1 dB of input
 *     while the wanted signal grows only 1 dB.
 *
 * The tuner is left at [RtlTcpProtocol.ADSB_TUNER_GAIN_TENTHS_DB] and
 * 1090 MHz when the run finishes, so a normal live session can follow.
 */
class RfDiagnostics(
    private val host: String = "127.0.0.1",
    private val port: Int = RtlTcpProtocol.DEFAULT_PORT,
    private val demodulator: IqDemodulator = IqDemodulator(),
    /** CRC check, injected so this module keeps no dependency on :adsb-decoder. */
    private val isCrcValid: (String) -> Boolean,
    private val bandTargets: List<BandTarget> = DefaultBandTargets,
    private val gainSteps: List<Int> = DefaultGainSteps,
) {

    fun run(): Flow<DiagnosticsProgress> = flow {
        val totalSteps = bandTargets.size + gainSteps.size
        var completed = 0

        RtlTcpClient(host, port).use { rtl ->
            val info = rtl.connect()
            rtl.setSampleRate(RtlTcpProtocol.ADSB_SAMPLE_RATE_HZ)
            rtl.setAgcMode(false)
            rtl.setGainMode(manual = true)

            // --- 1. Band scan at one fixed gain -------------------------------
            // Every band is measured at the same gain, which is what makes the
            // levels comparable to each other. A mid-scale gain keeps strong
            // carriers off the ADC rails while still lifting quiet bands above
            // the noise floor.
            rtl.setTunerGain(BandScanGainTenthsDb)
            val bands = mutableListOf<BandResult>()
            for (target in bandTargets) {
                emit(DiagnosticsProgress.Step(target.label, completed, totalSteps))
                rtl.setFrequency(target.frequencyHz)
                val metrics = measureLevel(rtl, BandSettleMillis, BandWindowMillis)
                bands += BandResult(target, metrics)
                completed++
            }

            // --- 2. Gain sweep on the ADS-B channel ---------------------------
            rtl.setFrequency(RtlTcpProtocol.ADSB_FREQUENCY_HZ)
            val gains = mutableListOf<GainResult>()
            for (gain in gainSteps) {
                emit(
                    DiagnosticsProgress.Step(
                        "1090 MHz @ ${"%.1f".format(gain / 10.0)} dB",
                        completed,
                        totalSteps,
                    )
                )
                rtl.setTunerGain(gain)
                gains += measureReception(rtl, gain, GainSettleMillis, GainWindowMillis)
                completed++
            }

            // Leave the dongle usable for a normal live session.
            rtl.setTunerGain(RtlTcpProtocol.ADSB_TUNER_GAIN_TENTHS_DB)

            emit(
                DiagnosticsProgress.Done(
                    DiagnosticsReport(
                        tunerName = info.tunerName,
                        bands = bands,
                        gains = gains,
                        findings = analyze(bands, gains),
                    )
                )
            )
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Discard [settleMillis] worth of samples, then accumulate level metrics
     * for [windowMillis]. The discard matters: rtl_tcp keeps streaming while a
     * retune command is in flight, so the first samples after [RtlTcpClient
     * .setFrequency] were still captured at the previous frequency.
     */
    private suspend fun measureLevel(
        rtl: RtlTcpClient,
        settleMillis: Long,
        windowMillis: Long,
    ): SignalMetrics {
        drain(rtl, settleMillis)
        val parts = mutableListOf<SignalMetrics>()
        withTimeoutOrNull(windowMillis) {
            rtl.samples().collect { parts += measureSignal(it) }
        }
        return parts.aggregate()
    }

    /** Level metrics plus decoder counters over the same window. */
    private suspend fun measureReception(
        rtl: RtlTcpClient,
        gainTenthsDb: Int,
        settleMillis: Long,
        windowMillis: Long,
    ): GainResult {
        drain(rtl, settleMillis)
        val parts = mutableListOf<SignalMetrics>()
        var preambles = 0
        var decoded = 0
        var valid = 0
        val startedAt = System.currentTimeMillis()
        withTimeoutOrNull(windowMillis) {
            rtl.samples().collect { chunk ->
                parts += measureSignal(chunk)
                val result = demodulator.demodulateDetailed(chunk)
                preambles += result.preambleMatches
                decoded += result.frames.size
                valid += result.frames.count { isCrcValid(it.hex) }
            }
        }
        return GainResult(
            gainTenthsDb = gainTenthsDb,
            metrics = parts.aggregate(),
            preambleMatches = preambles,
            framesDecoded = decoded,
            crcValidFrames = valid,
            windowMillis = System.currentTimeMillis() - startedAt,
        )
    }

    private suspend fun drain(rtl: RtlTcpClient, millis: Long) {
        withTimeoutOrNull(millis) { rtl.samples().collect { } }
    }

    companion object {
        /**
         * Japanese physical UHF TV channel N is centred at
         * `473.142857 + (N - 13) * 6` MHz. Tokyo Skytree carries all the
         * Kanto-area stations, and channel 25 is the one that matters here:
         * 545.142857 × 2 = 1090.29 MHz, essentially exactly the ADS-B channel.
         */
        fun uhfTvChannelHz(channel: Int): Int =
            (473_142_857L + (channel - 13) * 6_000_000L).toInt()

        val DefaultBandTargets: List<BandTarget> = listOf(
            BandTarget("FM 放送 (80 MHz)", 80_000_000, note = "参考: 低い方の帯域外エネルギー"),
            BandTarget("UHF ch16 TOKYO MX", uhfTvChannelHz(16)),
            BandTarget("UHF ch21 フジテレビ", uhfTvChannelHz(21)),
            BandTarget(
                "UHF ch25 日本テレビ",
                uhfTvChannelHz(25),
                note = "2倍波が 1090.3 MHz に一致",
                harmonicOfInterestHz = uhfTvChannelHz(25) * 2,
            ),
            BandTarget("UHF ch27 NHK 総合", uhfTvChannelHz(27)),
            BandTarget("携帯 800 MHz 帯", 800_000_000),
            BandTarget(
                "対照 1080 MHz",
                1_080_000_000,
                role = BandRole.QUIET_REFERENCE,
                note = "空きチャンネル。ノイズフロアの基準",
            ),
            BandTarget("ADS-B 1090 MHz", RtlTcpProtocol.ADSB_FREQUENCY_HZ, role = BandRole.ADSB),
            BandTarget(
                "対照 1100 MHz",
                1_100_000_000,
                role = BandRole.QUIET_REFERENCE,
                note = "空きチャンネル。ノイズフロアの基準",
            ),
        )

        /**
         * Spread of R820T/R828D gain values in tenths of a dB. The low end is
         * as important as the high end: on an overloaded site the low-gain
         * steps are the ones that still decode.
         */
        val DefaultGainSteps: List<Int> = listOf(0, 90, 166, 250, 328, 402, 445, 496)

        /** Fixed gain used for the whole band scan so levels stay comparable. */
        const val BandScanGainTenthsDb: Int = 250

        private const val BandSettleMillis = 350L
        private const val BandWindowMillis = 450L
        private const val GainSettleMillis = 400L
        private const val GainWindowMillis = 1_500L
    }
}
