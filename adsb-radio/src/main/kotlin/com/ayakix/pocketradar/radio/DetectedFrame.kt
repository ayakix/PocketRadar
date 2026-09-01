package com.ayakix.pocketradar.radio

/**
 * One Mode S frame produced by [IqDemodulator].
 *
 * @property sampleOffset Index (in the post-decimation 2 MS/s magnitude
 *   buffer) where the frame's preamble starts. Useful for splicing
 *   overlapping I/Q buffers and deduplicating frames found in the overlap.
 * @property hex Lowercase Mode S hex representation. 14 characters for short
 *   (DF in {0,4,5,11}) frames, 28 characters for long ones.
 */
data class DetectedFrame(
    val sampleOffset: Int,
    val hex: String,
)

/**
 * Result of one demodulation pass, including the detector statistics that
 * [IqDemodulator.demodulateDetailed] collects along the way.
 *
 * @property preambleMatches How many positions passed the preamble test.
 *   Always >= `frames.size`, since some matches fail to yield a frame.
 * @property samplesProcessed Length of the post-decimation magnitude buffer,
 *   i.e. the number of 2 MS/s samples the counters cover. Lets callers turn
 *   raw counts into rates per second.
 */
data class DemodulationResult(
    val frames: List<DetectedFrame>,
    val preambleMatches: Int,
    val samplesProcessed: Int,
) {
    companion object {
        val Empty = DemodulationResult(emptyList(), 0, 0)
    }
}
