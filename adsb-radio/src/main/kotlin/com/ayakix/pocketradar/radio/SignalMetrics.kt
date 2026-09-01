package com.ayakix.pocketradar.radio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Signal-level statistics computed straight from an interleaved u8 I/Q buffer,
 * before any demodulation. This is the layer where receiver *health* problems
 * become visible: a front end that is being overloaded looks completely
 * different here from one that simply has no signal, even though both produce
 * zero frames downstream.
 *
 * All values derive from raw ADC codes, so they describe what the RTL-SDR's
 * 8-bit converter actually sees.
 */
data class SignalMetrics(
    /** Number of I/Q sample pairs the metrics were computed over. */
    val sampleCount: Int,
    /** Pairs where I or Q hit an ADC rail (0 or 255). */
    val clippedSamples: Int,
    /** Mean L1 magnitude `|I-127| + |Q-127|`, range 0..254. */
    val meanMagnitude: Double,
    /** RMS of the same magnitude, sensitive to bursts the mean smooths over. */
    val rmsMagnitude: Double,
    /** Largest magnitude seen. */
    val peakMagnitude: Int,
    /** Mean of `I-127`. A large value means the tuner's DC offset has drifted. */
    val dcOffsetI: Double,
    /** Mean of `Q-127`. */
    val dcOffsetQ: Double,
) {

    /**
     * Fraction of samples pinned to an ADC rail. Anything above a fraction of
     * a percent means the converter is running out of range: the gain is too
     * high for what is arriving, and everything downstream is distorted.
     */
    val clipRate: Double
        get() = if (sampleCount == 0) 0.0 else clippedSamples.toDouble() / sampleCount

    /**
     * Mean level relative to ADC full scale, in dB. Deliberately *not*
     * calibrated to an absolute power: it is a relative yardstick for
     * comparing measurements taken at the same gain setting.
     */
    val meanLevelDbfs: Double
        get() = if (meanMagnitude <= MinMagnitude) FloorDbfs
        else 20.0 * log10(meanMagnitude / FullScaleMagnitude)

    val peakLevelDbfs: Double
        get() = if (peakMagnitude <= MinMagnitude) FloorDbfs
        else 20.0 * log10(peakMagnitude / FullScaleMagnitude)

    companion object {
        /** L1 magnitude of a full-scale sample: |127| + |127|. */
        const val FullScaleMagnitude: Double = 254.0

        private const val MinMagnitude = 0.01

        /** Reported instead of -infinity for a silent buffer. */
        const val FloorDbfs: Double = -60.0

        val Empty = SignalMetrics(0, 0, 0.0, 0.0, 0, 0.0, 0.0)
    }
}

/**
 * Compute [SignalMetrics] over an interleaved u8 I/Q buffer. An odd trailing
 * byte is ignored, matching [IqDemodulator]'s handling of split TCP chunks.
 */
fun measureSignal(iq: ByteArray): SignalMetrics {
    val n = iq.size / 2
    if (n == 0) return SignalMetrics.Empty

    var clipped = 0
    var magnitudeSum = 0.0
    var magnitudeSquareSum = 0.0
    var peak = 0
    var sumI = 0L
    var sumQ = 0L

    for (k in 0 until n) {
        val rawI = iq[2 * k].toInt() and 0xFF
        val rawQ = iq[2 * k + 1].toInt() and 0xFF
        if (rawI == 0 || rawI == 255 || rawQ == 0 || rawQ == 255) clipped++

        val di = rawI - 127
        val dq = rawQ - 127
        sumI += di
        sumQ += dq

        val magnitude = abs(di) + abs(dq)
        magnitudeSum += magnitude
        magnitudeSquareSum += magnitude.toDouble() * magnitude
        if (magnitude > peak) peak = magnitude
    }

    return SignalMetrics(
        sampleCount = n,
        clippedSamples = clipped,
        meanMagnitude = magnitudeSum / n,
        rmsMagnitude = sqrt(magnitudeSquareSum / n),
        peakMagnitude = peak,
        dcOffsetI = sumI.toDouble() / n,
        dcOffsetQ = sumQ.toDouble() / n,
    )
}

/**
 * Combine metrics from several buffers as if they had been one long buffer,
 * so a measurement window can span many TCP chunks.
 */
fun List<SignalMetrics>.aggregate(): SignalMetrics {
    val total = sumOf { it.sampleCount }
    if (total == 0) return SignalMetrics.Empty
    return SignalMetrics(
        sampleCount = total,
        clippedSamples = sumOf { it.clippedSamples },
        meanMagnitude = sumOf { it.meanMagnitude * it.sampleCount } / total,
        // RMS combines through the mean of squares, never the mean of RMS values.
        rmsMagnitude = sqrt(sumOf { it.rmsMagnitude * it.rmsMagnitude * it.sampleCount } / total),
        peakMagnitude = maxOf { it.peakMagnitude },
        dcOffsetI = sumOf { it.dcOffsetI * it.sampleCount } / total,
        dcOffsetQ = sumOf { it.dcOffsetQ * it.sampleCount } / total,
    )
}
