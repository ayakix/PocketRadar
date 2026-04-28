package com.ayakix.pocketradar.radio

import com.ayakix.pocketradar.decoder.AdsbDecoder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke tests against the real RTL-SDR capture in `iq_capture.bin` (~5 s of
 * 2.4 MS/s I/Q from a Tokyo session). The demodulator is intentionally simple
 * and is expected to emit many false-positive frame candidates; CRC verification
 * (delegated to `AdsbDecoder` from `:adsb-decoder`) filters the noise.
 */
class IqDemodulatorTest {

    @Test
    fun `demodulator emits frame candidates from the captured I-Q fixture`() {
        val iq = loadFixture("/iq_capture.bin")
        val demod = IqDemodulator()
        val frames = demod.demodulate(iq)

        println("=== IqDemodulator fixture run ===")
        val seconds = iq.size / 2.0 / demod.sampleRateHz
        println("Input: ${iq.size} bytes (~${"%.2f".format(seconds)} s @ ${demod.sampleRateHz} Hz)")
        println("Frame candidates detected: ${frames.size}")

        val byDf = frames.groupingBy { (it.hex.substring(0, 2).toInt(16)) ushr 3 }.eachCount()
        println("DF distribution (raw, before CRC):")
        byDf.toSortedMap().forEach { (df, count) -> println("  DF=$df : $count") }

        assertTrue(frames.isNotEmpty(), "Expected at least one frame candidate, got 0")
        assertTrue(
            frames.all { it.hex.length == 14 || it.hex.length == 28 },
            "Frame lengths must be 14 (short) or 28 (long); got: " +
                frames.map { it.hex.length }.distinct(),
        )
    }

    @Test
    fun `demodulated frames pass CRC verification at the expected rate`() {
        val iq = loadFixture("/iq_capture.bin")
        val demod = IqDemodulator()
        val frames = demod.demodulate(iq)

        // Run each candidate through AdsbDecoder, which performs CRC-24
        // verification and only returns non-null on a clean DF=17 frame.
        val decoder = AdsbDecoder(staleAfterMillis = Long.MAX_VALUE / 2)
        var validAdsb = 0
        var t = 0L
        for (f in frames) {
            if (decoder.ingest(f.hex, t) != null) validAdsb++
            t += 1
        }

        val snapshot = decoder.snapshot(t)
        println("=== CRC pass-rate ===")
        println("Frame candidates: ${frames.size}")
        println("DF=17 with valid CRC: $validAdsb")
        println("Unique aircraft tracked: ${snapshot.size}")
        snapshot.forEach { ac ->
            println(
                "  ${ac.icao}  callsign=${ac.callsign ?: "-"}  " +
                    "lat=${ac.latitude}  lon=${ac.longitude}  " +
                    "alt=${ac.altitudeFeet}  spd=${ac.groundSpeedKnots}",
            )
        }

        // Bar tightened from `>= 1` to `>= 3`. The fixture is deterministic,
        // so a regression that breaks the demodulator down to 1–2 frames
        // would have slipped through the looser check.
        assertTrue(
            validAdsb >= 3,
            "Expected ≥ 3 CRC-valid DF=17 frames, got $validAdsb out of ${frames.size} candidates",
        )
        assertTrue(
            snapshot.size >= 2,
            "Expected ≥ 2 unique aircraft tracked end-to-end, got ${snapshot.size}",
        )
    }

    private fun loadFixture(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream(name)
            ?: error("Fixture $name not found in test resources")
        return stream.use { it.readBytes() }
    }
}
