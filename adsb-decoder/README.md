# adsb-decoder

A pure Kotlin/JVM library that decodes **1090 MHz ADS-B Mode S Extended Squitter**
messages into structured `Aircraft` records — ICAO address, callsign, position,
altitude, ground speed, track, and vertical rate.

Zero Android (or other platform) dependencies — the library runs on any JVM and
is straightforward to compile for Kotlin Multiplatform targets.

## Scope

**Supported**
- Mode S CRC-24 verification (generator polynomial `0xFFF409`)
- DF=17 (Extended Squitter) parsing
  - TC 1–4: aircraft identification (callsign)
  - TC 9–18: airborne position with barometric altitude
  - TC 19 subtypes 1–2: ground-speed velocity (track, vertical rate)
- CPR **globally unambiguous** position decoding (one even + one odd frame within ~10 s)
- Stateful aggregator that builds per-aircraft snapshots from a message stream

**Not yet supported**
- TC 5–8 (surface position)
- TC 19 subtypes 3–4 (airspeed-with-heading)
- TC 20–22 (airborne position with GNSS altitude)
- CPR **locally unambiguous** decoding using a known reference position
- I/Q stream demodulation — the library expects already-demodulated hex strings,
  e.g. the output of `dump1090 --raw` or the `:adsb-radio` demodulator

## Usage

### Add as a Gradle subproject

In the root `settings.gradle.kts`:
```kotlin
include(":adsb-decoder")
```

In a consumer module's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":adsb-decoder"))
}
```

### Decode a stream of messages

```kotlin
import com.ayakix.pocketradar.decoder.AdsbDecoder

val decoder = AdsbDecoder()
val t0 = System.currentTimeMillis()

// Either bare hex or the dump1090 wire format (`*…;`) is accepted.
decoder.ingest("*8D4840D6202CC371C32CE0576098;", t0)        // TC=4 callsign
decoder.ingest("8d71c7095875e3e5a95127f79925", t0 + 100)    // TC=11 even
decoder.ingest("8d71c7095875d77f9e8a4aa03e8d", t0 + 200)    // TC=11 odd
decoder.ingest("8d71c709990d620ab05c1c6efe5e", t0 + 300)    // TC=19 velocity

decoder.snapshot(nowMillis = t0 + 400).forEach { ac ->
    println(
        "${ac.icao} ${ac.callsign ?: "-"} " +
            "${ac.latitude}°/${ac.longitude}° " +
            "alt=${ac.altitudeFeet}ft spd=${ac.groundSpeedKnots}kt"
    )
}
```

### Configuration

`AdsbDecoder` exposes two timing knobs:

| Parameter | Default (ms) | Meaning |
|---|---|---|
| `cprPairTtlMillis` | `10_000` | Maximum age difference between an even/odd CPR pair to still be combinable |
| `staleAfterMillis` | `60_000` | Aircraft are dropped from `snapshot()` after this much silence |

## Architecture

```
hex string ──► Hex.hexToByteArray ──► Crc24.syndrome
                                          │
                                          ▼
                                       AdsbFrame      (DF=17 ✓, CA, ICAO, TC, ME)
                                          │
                          ┌───────────────┼───────────────┐
                          ▼               ▼               ▼
                IdentificationDecoder   AirbornePosition   AirborneVelocity
                          │             Decoder            Decoder
                          │               │                   │
                          ▼               ▼                   ▼
                       callsign      CPR even/odd       speed / track /
                                     + altitude         vertical rate
                                          │
                                          ▼
                                       CprDecoder
                                     (global decode)
                                          │
                          ┌───────────────┴───────────────┐
                          ▼                               ▼
                                 AdsbDecoder
                          (per-ICAO state, stale TTL)
                                          │
                                          ▼
                                      Aircraft
```

Each decoder is a stateless `object` with a single public function, so the call
site reads linearly. State — CPR pair tracking, stale TTL — lives only in
`AdsbDecoder`.

## Building and testing

Standard Kotlin/JVM Gradle module.

```sh
./gradlew :adsb-decoder:build         # compile + test
./gradlew :adsb-decoder:test          # tests only
./gradlew :adsb-decoder:test --info   # detailed test output
```

Requires JDK 17. The project's root `settings.gradle.kts` enables Foojay's
`foojay-resolver-convention`, so a matching toolchain is downloaded automatically
on first run if the host JDK does not match.

### Test fixtures

`src/test/resources/sample_messages.txt` is a real RTL-SDR capture from Tokyo:
**2,995 Mode S messages**, of which **705 are DF=17 ADS-B**, covering **15 unique
aircraft**. The integration test
`AdsbDecoderTest > replays the entire captured fixture and tracks every DF-17 aircraft`
streams every line through the decoder and asserts the expected number of
aircraft, positions, velocities, and callsigns are recovered.

Per-decoder tests cross-check against the canonical *pyModeS* textbook sample
`8D4840D6202CC371C32CE0576098` (KLM 1023) so the implementation can be verified
against any other reference without the captured fixture.

## References

- *The 1090 Megahertz Riddle* — Junzi Sun. Primary reference for ADS-B / Mode S / CPR.
- [`junzis/pyModeS`](https://github.com/junzis/pyModeS) — Python implementation, used to cross-check expected values in tests.
- [OpenSky Network `java-adsb`](https://github.com/openskynetwork/java-adsb) — Java decoder, useful as a design reference.
- ICAO Annex 10 Volume IV / RTCA DO-260B — official Mode S / ADS-B specifications.

## License

[MIT](../LICENSE) — Copyright (c) 2026 ayakix.
