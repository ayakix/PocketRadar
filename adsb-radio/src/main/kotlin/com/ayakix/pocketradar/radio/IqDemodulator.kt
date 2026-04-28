package com.ayakix.pocketradar.radio

import kotlin.math.abs

/**
 * Demodulates an interleaved u8 I/Q stream into Mode S frames (hex strings).
 *
 * The algorithm follows the dump1090 reference: it works on **magnitude** at
 * **2 MS/s** so that 1 bit lines up with exactly 2 samples and 0.5 μs lines up
 * with exactly 1 sample. RTL-SDR's natural ADS-B sample rate is 2.4 MS/s, so
 * we decimate 6:5 (drop every 6th I/Q pair) on the way in.
 *
 * Magnitude is computed with the cheap L1 approximation `|I-127| + |Q-127|`,
 * which is fast and sufficient for preamble detection on 8-bit samples.
 *
 * Pipeline:
 *   1. **Magnitude**: `|I-127| + |Q-127|` per sample.
 *   2. **Preamble detection**: dump1090's neighbour-comparison pattern. The
 *      8 μs preamble has high pulses at 0.0, 1.0, 3.5 and 4.5 μs and is silent
 *      elsewhere; at 2 MS/s the high pulses sit on samples 0, 2, 7, 9.
 *   3. **PPM bit slicing**: each bit spans 2 samples; first-sample-high → 1,
 *      second-sample-high → 0.
 *   4. **Frame length** is decided by the Downlink Format in the first byte.
 *      DF in {0, 4, 5, 11} → short (56 bits, 14 hex chars); otherwise long
 *      (112 bits, 28 hex chars).
 *
 * The CRC is **not** verified here. The consumer (typically `AdsbDecoder` from
 * `:adsb-decoder`) does that and filters out the false positives this
 * demodulator inevitably produces.
 */
class IqDemodulator(
    val sampleRateHz: Int = 2_400_000,
) {

    init {
        require(sampleRateHz == 2_000_000 || sampleRateHz == 2_400_000) {
            "Supported rates: 2_000_000 or 2_400_000 Hz; got $sampleRateHz"
        }
    }

    /**
     * Demodulate a complete I/Q buffer and return all Mode S frames found
     * inside. Each [DetectedFrame] carries both the lowercase hex string and
     * the sample offset (in the **post-decimation** magnitude buffer at
     * 2 MS/s) where the preamble starts — useful for callers that splice
     * overlapping buffers and want to deduplicate.
     */
    fun demodulate(iq: ByteArray): List<DetectedFrame> {
        // TCP reads from rtl_tcp can split an I/Q sample pair across two
        // chunks, so an odd-length buffer is normal in production. The
        // magnitude routines floor to (size / 2) samples, silently dropping
        // a trailing single byte; the caller (RtlTcpMessageSource) keeps
        // that byte in its carry-over tail and re-combines it on the next
        // chunk so the I/Q pair gets restored.
        if (iq.size < 2) return emptyList()
        val mag = if (sampleRateHz == 2_400_000) decimateAndMagnitude24To20(iq)
        else magnitudeAt20(iq)
        return demodulateAt2Mhz(mag)
    }

    /** Number of post-decimation samples in [byteCount] input bytes. */
    fun outputSamplesFor(byteCount: Int): Int {
        val inputSamples = byteCount / 2
        return if (sampleRateHz == 2_400_000) inputSamples * 5 / 6 else inputSamples
    }

    // ---- Magnitude --------------------------------------------------------------

    /**
     * Compute magnitude while decimating 2.4 → 2.0 MS/s by dropping every 6th
     * I/Q sample pair (5-out-of-6 keep ratio). Crude, but sufficient for
     * Mode S whose occupied bandwidth (~2 MHz) is well within the new
     * Nyquist (1 MHz around DC).
     */
    private fun decimateAndMagnitude24To20(iq: ByteArray): IntArray {
        val samplesIn = iq.size / 2
        val samplesOut = samplesIn * 5 / 6
        val mag = IntArray(samplesOut)
        var inIdx = 0
        var outIdx = 0
        var cycle = 0
        while (outIdx < samplesOut && inIdx < iq.size - 1) {
            if (cycle < 5) {
                val di = (iq[inIdx].toInt() and 0xFF) - 127
                val dq = (iq[inIdx + 1].toInt() and 0xFF) - 127
                mag[outIdx++] = abs(di) + abs(dq)
            }
            inIdx += 2
            cycle = (cycle + 1) % 6
        }
        return mag
    }

    private fun magnitudeAt20(iq: ByteArray): IntArray {
        val n = iq.size / 2
        val mag = IntArray(n)
        for (k in 0 until n) {
            val di = (iq[2 * k].toInt() and 0xFF) - 127
            val dq = (iq[2 * k + 1].toInt() and 0xFF) - 127
            mag[k] = abs(di) + abs(dq)
        }
        return mag
    }

    // ---- Demodulation at 2 MS/s -------------------------------------------------

    private fun demodulateAt2Mhz(mag: IntArray): List<DetectedFrame> {
        val results = mutableListOf<DetectedFrame>()
        val maxStart = mag.size - PREAMBLE_SAMPLES - LONG_PAYLOAD_SAMPLES
        var i = 0
        while (i < maxStart) {
            if (matchesPreamble(mag, i)) {
                val payloadStart = i + PREAMBLE_SAMPLES
                val frame = decodeFrame(mag, payloadStart)
                if (frame == null) {
                    i++
                    continue
                }
                results += DetectedFrame(sampleOffset = i, hex = frame)
                // Always skip the full long-frame window we actually consumed,
                // even when the trimmed result was a 56-bit short frame —
                // otherwise we'd re-scan the back half of the same payload.
                i = payloadStart + LONG_PAYLOAD_SAMPLES + 1
            } else {
                i++
            }
        }
        return results
    }

    /**
     * dump1090-style preamble detection. The 8 μs preamble at 2 MS/s spans 16
     * samples; the four high pulses sit on samples 0, 2, 7, 9 and the rest
     * must be lower. Verifying the local shape is much more selective than
     * an absolute threshold and is robust to the receiver's AGC drift.
     */
    private fun matchesPreamble(mag: IntArray, start: Int): Boolean {
        val m0 = mag[start + 0]
        val m1 = mag[start + 1]
        val m2 = mag[start + 2]
        val m3 = mag[start + 3]
        val m4 = mag[start + 4]
        val m5 = mag[start + 5]
        val m6 = mag[start + 6]
        val m7 = mag[start + 7]
        val m8 = mag[start + 8]
        val m9 = mag[start + 9]

        // Local-shape check (dump1090's neighbour comparison).
        if (!(m0 > m1 &&
                m1 < m2 &&
                m2 > m3 &&
                m3 < m0 &&
                m4 < m0 &&
                m5 < m0 &&
                m6 < m0 &&
                m7 > m8 &&
                m8 < m9 &&
                m9 > m6)) return false

        // Strength check: the four pulse samples must clearly dominate the
        // silent middle samples. Without this, random noise that happens to
        // wiggle in the right shape gets accepted and the CRC pass rate
        // craters. A 2× ratio is conservative; tighten to 3× if false
        // positives still dominate.
        val pulseMin = minOf(m0, m2, m7, m9)
        val silenceMax = maxOf(m4, m5)
        return pulseMin >= silenceMax * 2
    }

    /**
     * PPM-decode 112 bits at 2 MS/s. 1 bit = 2 samples; first-sample-high → 1,
     * second-sample-high → 0. The frame is then trimmed to 56 or 112 bits
     * based on the Downlink Format.
     */
    private fun decodeFrame(mag: IntArray, payloadStart: Int): String? {
        val bytes = ByteArray(14)
        for (b in 0 until 112) {
            val first = mag[payloadStart + 2 * b]
            val second = mag[payloadStart + 2 * b + 1]
            if (first == 0 && second == 0) return null // dead air → not a real frame
            if (first > second) {
                bytes[b / 8] = (bytes[b / 8].toInt() or (1 shl (7 - b % 8))).toByte()
            }
        }

        val df = (bytes[0].toInt() and 0xFF) ushr 3
        val length = if (df in SHORT_FRAME_DFS) 7 else 14

        val sb = StringBuilder(length * 2)
        for (k in 0 until length) {
            sb.append("%02x".format(bytes[k].toInt() and 0xFF))
        }
        return sb.toString()
    }

    companion object {
        /** 8 μs preamble at 2 MS/s = 16 samples. */
        private const val PREAMBLE_SAMPLES = 16

        /** 112 bits × 2 samples/bit = 224 samples. */
        private const val LONG_PAYLOAD_SAMPLES = 224

        /** DFs that use the 56-bit short frame format. */
        private val SHORT_FRAME_DFS = setOf(0, 4, 5, 11)
    }
}
