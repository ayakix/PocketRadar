package com.ayakix.pocketradar.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdsbDecoderTest {

    @Test
    fun `ignores short Mode S frames`() {
        val decoder = AdsbDecoder()
        // *5d86da38aa7c0b; — DF=11 All-Call Reply, not ADS-B.
        assertNull(decoder.ingest("*5d86da38aa7c0b;", timestampMillis = 0))
    }

    @Test
    fun `accepts dump1090 wire format with leading asterisk and trailing semicolon`() {
        val decoder = AdsbDecoder()
        val updated = decoder.ingest("*8D4840D6202CC371C32CE0576098;", timestampMillis = 0)
        assertNotNull(updated)
        assertEquals("KLM1023", updated.callsign)
    }

    @Test
    fun `merges identification, position, and velocity for a single aircraft`() {
        val decoder = AdsbDecoder()
        // ICAO 71C709 — fed in roughly the order they appeared in the capture.
        decoder.ingest("8d71c70925414075c34820b7d309", timestampMillis = 0)      // TC=4 callsign
        decoder.ingest("8d71c7095875e3e5a95127f79925", timestampMillis = 100)    // TC=11 even
        decoder.ingest("8d71c7095875d77f9e8a4aa03e8d", timestampMillis = 200)    // TC=11 odd
        decoder.ingest("8d71c709990d620ab05c1c6efe5e", timestampMillis = 300)    // TC=19 velocity

        val snapshot = decoder.snapshot(nowMillis = 400)
        assertEquals(1, snapshot.size)
        val a = snapshot.single()

        assertEquals("71C709", a.icao.toString())
        assertEquals("PTA504", a.callsign)
        assertEquals(363, a.groundSpeedKnots)
        assertEquals(VerticalRateSource.BAROMETRIC, a.verticalRateSource)
        assertNotNull(a.latitude)
        assertNotNull(a.longitude)
        // Roughly Tokyo Bay (the captured location).
        assertTrue(a.latitude in 35.0..36.5, "latitude out of expected range: ${a.latitude}")
        assertTrue(a.longitude in 139.0..141.0, "longitude out of expected range: ${a.longitude}")
    }

    @Test
    fun `replays the entire captured fixture and tracks every DF-17 aircraft`() {
        // ~150s of simulated time at 50ms per message — bump the stale TTL so
        // aircraft seen only at the start of the file are still in the snapshot.
        val decoder = AdsbDecoder(staleAfterMillis = Long.MAX_VALUE / 2)
        val resource = javaClass.getResourceAsStream("/sample_messages.txt")
            ?: error("sample_messages.txt not found in test resources")

        var t = 0L
        resource.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                decoder.ingest(line, timestampMillis = t)
                t += 50  // assume 50 ms between messages — well within CPR pair TTL
            }
        }

        val snapshot = decoder.snapshot(nowMillis = t)

        // Capture had 15 unique DF=17 ICAOs (verified earlier with awk).
        assertEquals(15, snapshot.size, "should track all 15 ADS-B aircraft")

        // Every captured aircraft should at minimum have been seen and have a
        // last-seen timestamp set.
        assertTrue(snapshot.all { it.lastSeenMillis > 0 })

        // Counts derived from the capture itself: 3 aircraft sent identification,
        // 13 sent airborne position, 14 sent velocity. Allow some slack because
        // CPR pairing may miss a frame at the very edges of the file.
        val withCallsign = snapshot.count { it.callsign != null }
        val withPosition = snapshot.count { it.latitude != null && it.longitude != null }
        val withVelocity = snapshot.count { it.groundSpeedKnots != null }
        assertEquals(3, withCallsign, "expected callsign for 3 aircraft, got $withCallsign")
        // Position requires an even+odd CPR pair within 10s — a few aircraft only
        // sent one parity in the captured window, so we allow some misses.
        assertTrue(withPosition >= 8, "expected position for ≥8 aircraft, got $withPosition")
        assertTrue(withVelocity >= 10, "expected velocity for ≥10 aircraft, got $withVelocity")
    }

    @Test
    fun `stale aircraft drop out of snapshot`() {
        val decoder = AdsbDecoder(staleAfterMillis = 5_000L)
        decoder.ingest("8D4840D6202CC371C32CE0576098", timestampMillis = 0)
        assertEquals(1, decoder.snapshot(nowMillis = 4_000L).size)
        // 6 seconds later, the aircraft is past the TTL.
        assertEquals(0, decoder.snapshot(nowMillis = 6_000L).size)
    }

    @Test
    fun `discards messages with corrupted CRC`() {
        val decoder = AdsbDecoder()
        // Flip a payload bit: original sample CRC will no longer verify.
        val good = "8D4840D6202CC371C32CE0576098"
        val corrupted = good.substring(0, 10) + "F" + good.substring(11)  // change byte 5
        assertNull(decoder.ingest(corrupted, timestampMillis = 0))
        assertEquals(0, decoder.snapshot(nowMillis = 0).size)
    }
}
