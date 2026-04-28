# adsb-radio

The "physical layer" companion to [`adsb-decoder`](../adsb-decoder/README.md).

`adsb-radio` connects to an `rtl_tcp` server, configures the tuner for ADS-B
reception, streams I/Q samples, demodulates them into Mode S frames, and exposes
the result as `Flow<String>` (hex). The downstream `adsb-decoder` then turns
each hex frame into an `Aircraft`.

## Status

| Phase | Component | State |
|---|---|---|
| **3A** | `RtlTcpClient` — rtl_tcp protocol client | ✅ implemented |
| **3B** | `IqDemodulator` — I/Q → Mode S frames (PPM, preamble detection) | ✅ implemented |
| **3B** | `RtlTcpMessageSource` — TCP + DSP → `Flow<String>` | ✅ implemented |

## Phase 3A: `RtlTcpClient`

A pure Kotlin/JVM client for the rtl_tcp wire format used by:
- `librtlsdr`'s `rtl_tcp` binary (any platform), and
- the Android **"SDR driver"** app (package `marto.rtl_tcp_andro`).

### Wire protocol

```
                    rtl_tcp server
client connects ───────────────►
                    ◄─── 4 bytes  "RTL0" magic
                    ◄─── 4 bytes  tuner type   (big-endian uint32)
                    ◄─── 4 bytes  gain stages  (big-endian uint32)
                    ◄═══ continuous stream of u8 I, u8 Q samples ═══

client may send 5-byte commands at any time:
                    ───► 1 byte   command code
                    ───► 4 bytes  parameter   (big-endian uint32)
```

### Usage

```kotlin
import com.ayakix.pocketradar.radio.RtlTcpClient

// Default port is 14423 (the SDR driver app's default). Pass port = 1234
// explicitly when talking to upstream librtlsdr's rtl_tcp.
RtlTcpClient(host = "localhost").use { rtl ->
    val info = rtl.connect()
    println("Tuner: ${info.tunerName}, gain stages: ${info.gainStageCount}")

    // Standard ADS-B tuning: 1090 MHz, 2.4 MS/s, manual gain ~49 dB, AGC off.
    rtl.applyAdsbDefaults()

    rtl.samples().collect { iqChunk ->
        // iqChunk is interleaved u8 I, u8 Q. Phase 3B will demodulate this.
        process(iqChunk)
    }
}
```

`RtlTcpClient` is a [`Closeable`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Closeable.html);
the `use { ... }` block guarantees the socket is released even on exceptions.

### Command surface

| Method | rtl_tcp code | Notes |
|---|---|---|
| `setFrequency(hz)` | `0x01` | ADS-B = `1_090_000_000` |
| `setSampleRate(hz)` | `0x02` | 2.4 MS/s for ADS-B |
| `setGainMode(manual)` | `0x03` | `false` = AGC, `true` = manual |
| `setTunerGain(tenthsOfDb)` | `0x04` | e.g. `490` for 49 dB |
| `setAgcMode(on)` | `0x08` | RTL2832U's digital AGC |
| `applyAdsbDefaults()` | (composite) | Recommended starting point for ADS-B |

The full rtl_tcp command list lives in librtlsdr's `rtl_tcp.c`; only the
ADS-B-relevant ones are exposed here.

## Phase 3B: `IqDemodulator` and `RtlTcpMessageSource`

`IqDemodulator` turns u8 I/Q bytes into Mode S hex frames using the dump1090
algorithm internally:

1. **Magnitude** via the L1 approximation `|I-127| + |Q-127|`.
2. **Decimate** 2.4 MS/s → 2.0 MS/s by dropping every 6th I/Q pair, so 1 bit
   lines up with exactly 2 samples (and 0.5 μs with 1 sample).
3. **Preamble detection**: the 8 μs preamble has high pulses at samples
   0, 2, 7, 9 and silence elsewhere. We require both the local-shape
   pattern (`m[0] > m[1] && m[1] < m[2] && ...`) and a 2× strength
   margin between the dimmest pulse and the brightest silent sample.
4. **PPM bit slicing**: each bit spans 2 samples; first-sample-high → 1,
   second-sample-high → 0.
5. **Frame length**: trim to 56 bits for DF ∈ {0, 4, 5, 11}, otherwise 112.

CRC verification is **not** performed here — the consumer (typically
`AdsbDecoder` from `:adsb-decoder`) does that and filters out false positives.

### Recall expectations

The demodulator is intentionally simple (no error correction, no fancy
peak-locking). On a 5-second 2.4 MS/s capture from a typical urban antenna,
expect roughly **5–15 CRC-valid DF=17 frames** and **2–5 unique aircraft**.
dump1090 will produce ~3–5× more from the same recording, but adding
1-bit error correction and signal-quality scoring is out of scope for this
educational module.

### `RtlTcpMessageSource`

```kotlin
import com.ayakix.pocketradar.radio.RtlTcpMessageSource

val source = RtlTcpMessageSource()  // localhost:14423 by default
source.stream().collect { hex ->
    println(hex)  // pass to AdsbDecoder.ingest()
}
```

The class chains `RtlTcpClient` and `IqDemodulator` and exposes the same
`Flow<String>` interface as the Phase 2 `MockMessageSource`, so the `:app`
module can switch between the captured fixture and live reception by
swapping the source instance.

## Building and testing

```sh
./gradlew :adsb-radio:test          # spins up an in-process fake rtl_tcp server
./gradlew :adsb-radio:build         # compile + test
```

Pure JVM. JDK 17 via Foojay toolchain resolver. No Android dependency.

The tests use a tiny in-process `ServerSocket` that speaks just enough of the
rtl_tcp wire format to verify hello-header parsing, command framing, and the
sample stream's `Flow` semantics.

## Dependencies

- `kotlinx-coroutines-core` (`Flow`, `Dispatchers.IO`)

This module does **not** depend on `:adsb-decoder`. The Phase 3B demodulator
will emit hex frames as plain `String`s (one per Mode S frame), and `:app`
wires them into `:adsb-decoder` separately. Keeping the dependency arrows
strictly `:app → {:adsb-radio, :adsb-decoder}` lets either module evolve
without rebuilding the other.

## License

[MIT](../LICENSE) — Copyright (c) 2026 ayakix.
