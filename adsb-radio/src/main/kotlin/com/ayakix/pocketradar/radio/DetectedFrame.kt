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
