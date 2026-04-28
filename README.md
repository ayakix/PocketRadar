# PocketRadar

Real-time aircraft tracker for Android, powered by an RTL-SDR USB dongle.

## Overview

PocketRadar receives **1090 MHz ADS-B (Automatic Dependent Surveillance-Broadcast)** signals
through an RTL-SDR USB dongle attached to an Android device, decodes them, and plots aircraft
on Google Maps in real time.

The project is **also a hands-on technical tutorial**. The Mode S / ADS-B decoder, CPR
position reconstruction, and CRC-24 verification are all implemented from scratch in pure
Kotlin so that the protocol can be studied at the bit level instead of being treated as a
black box.

## Goals

- **Receive** ADS-B Extended Squitter (DF=17) frames at 1090 MHz on an Android device.
- **Decode** Mode S frames in pure Kotlin: CRC-24 verification, Type Code parsing, CPR
  airborne-position reconstruction, velocity / heading extraction.
- **Visualize** aircraft on Google Maps with rotated markers, flight trails, and detail
  sheets.
- **Teach** through readable, heavily commented code that exposes the protocol structure
  using bit operations.

## High-level Architecture

```
[1090 MHz Antenna] --coax--> [RTL-SDR Blog V4] --USB OTG--> [Android device]
                                                                  |
                                                                  +-- SDR driver app
                                                                  |   (marto.rtl_tcp_andro)
                                                                  |   exposes localhost:1234
                                                                  |
                                                                  +-- PocketRadar
                                                                      |  TCP I/Q reader
                                                                      |  PPM demodulator (P3)
                                                                      |  Mode S decoder (P1)
                                                                      |  Aircraft store (Flow)
                                                                      |  Compose + Maps UI
```

PocketRadar **does not access the USB device directly**. The Android app
**"SDR driver"** (originally "RTL2832U Driver" by Martin Marinov) handles USB permission and
RTL2832U / R828D chip initialization, and exposes raw I/Q samples through an `rtl_tcp` TCP
server on `localhost:1234`. PocketRadar connects to that server as a TCP client.

This separation keeps PocketRadar focused on signal processing and UI, and reuses a
well-tested USB driver instead of reimplementing it.

## Hardware

| Item | Details |
|---|---|
| Receiver | RTL-SDR Blog V4 (R828D tuner) — <https://ja.aliexpress.com/item/1005005952682051.html> |
| Antenna | 1090 MHz 6 dBi outdoor fiberglass antenna — <https://ja.aliexpress.com/item/1005004791556100.html> |
| Coaxial cable | RG58, N-female to SMA-male — <https://amzn.to/4sVo1bX> |
| USB OTG adapter | Type-C male to USB-A female — <https://amzn.to/48mfEyU> |
| Powered OTG cable / hub | **Strongly recommended.** The RTL-SDR V4 draws ~280–300 mA, which is unstable on most Android phones using bus-powered OTG. Use a Y-cable with an external power input, or a USB-PD docking hub. |

**Connection chain:**

```
Android (USB-C) --[powered OTG adapter]-- RTL-SDR Blog V4 --[RG58 coax]-- 1090 MHz antenna
```

**Verified host-side setup:** macOS + Homebrew `dump1090-fa` successfully decoded aircraft
(e.g. ICAO `A14104`) over Tokyo using this exact hardware chain.

## Software prerequisites

### On the Android device
- Android 14 or later (`minSdk = 34`).
- Google Play services with a Maps API key configured (see Build environment below).
- **For Phase 3 only** — the **SDR driver** app installed and bound to the dongle:
  - Google Play: <https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro>
  - Package name: `marto.rtl_tcp_andro` (formerly known as "RTL2832U Driver")
  - Source: <https://github.com/martinmarinov/rtl_tcp_andro->
  - On first connection, accept the USB permission dialog and choose to open the dongle
    with this app by default.
  - Phase 2 replays a captured fixture and does not require the SDR driver app or any
    USB hardware.

### On macOS (development and Phase 2 fixture capture)
```sh
brew install rtl-sdr dump1090-fa   # Installed binary is named `dump1090`
rtl_test -t                        # Should report "RTL-SDR Blog V4 Detected"
dump1090 --interactive             # Live decoding sanity check (terminal UI)

# Capture raw Mode S messages for Phase 2 test fixtures:
dump1090 --raw > captured_messages.txt
# Stop with Ctrl-C once enough messages have been recorded, then copy the file
# into app/src/test/resources/ and app/src/main/assets/ for replay.
```

### Build environment
- Android Studio (latest stable) with Kotlin 2.1.x and AGP 8.13.
- Gradle 8.14, JDK 17 (downloaded automatically via Foojay toolchain resolver).
- Jetpack Compose (BOM 2026.04.01), Material 3 with Dynamic Color.
- Coroutines and Flow (StateFlow) for concurrency.
- No DI framework — `ViewModelProvider.Factory` injects collaborators directly to keep
  the educational footprint minimal.
- Google Maps SDK for Android (`maps-compose`).

### Maps API key

The map view requires a Google Maps API key. The key is read at build time from
`local.properties` (gitignored) by the `secrets-gradle-plugin` and substituted into
`AndroidManifest.xml` as `${MAPS_API_KEY}`.

1. Visit the [Google Cloud Console](https://console.cloud.google.com/google/maps-apis),
   create or select a project, and enable **Maps SDK for Android**.
2. Create an API key under **Credentials → Create credentials → API key**. You can
   optionally restrict it to your debug keystore SHA-1 + the application id
   `com.ayakix.pocketradar`.
3. Open `local.properties` at the repository root (Android Studio creates it
   automatically on first sync; otherwise `touch local.properties`) and add a line
   with your key:

   ```properties
   MAPS_API_KEY=AIza...your_actual_key_here
   ```

`local.properties` is in `.gitignore`, so the key never leaves your machine. If the
key is missing, the build still succeeds (the placeholder in `local.defaults.properties`
is used), but the map tiles refuse to load at runtime — you will see a blank grey
canvas with the markers floating on top.

## Development phases

The project is implemented **back-to-front** so that each phase produces a working,
testable artifact.

| Phase | Scope | Hardware required |
|---|---|---|
| **Phase 1** | Mode S hex → `Aircraft` decoder. CRC-24, DF=17 parser, Type Code branches, CPR pair handling. JUnit-driven, runs on the JVM. | None |
| **Phase 2** | UI + Google Maps. Replays a captured-message fixture (from `dump1090 --raw`) through the Phase 1 decoder and renders aircraft on the map. Runs on emulator or device. | None (uses captured fixture) |
| **Phase 3** | Real-hardware integration. TCP connection to the **SDR driver** app's `rtl_tcp` server, I/Q stream → Mode S frames in Kotlin (preamble detection, PPM demodulation), wired into the Phase 1 decoder and Phase 2 UI for end-to-end live tracking. | Android device + RTL-SDR + antenna |

The detailed task breakdown lives in `plan.md`.

## ADS-B / Mode S Protocol Reference

This section is a quick map of what the Phase 1 decoder needs to understand. It is
intentionally compact; *The 1090 Megahertz Riddle* is the long-form reference.

### What an ADS-B message is

Aircraft transponders broadcast **Mode S** signals on **1090 MHz**. ADS-B is one specific
flavor of Mode S messages — namely **Downlink Format 17 (DF=17), the Extended Squitter** —
that carries position, altitude, velocity, and identification of the aircraft.

A Mode S frame on the wire is either **56 bits (short)** or **112 bits (long)**.
ADS-B messages are always 112 bits long. `dump1090 --raw` writes them as
`*` + 28 hex characters + `;`.

### Bit layout of a 112-bit Mode S frame

```
 bit:    0     5    8                     32                                          88           112
         ┌─────┬────┬──────────────────────┬────────────────────────────────────────────┬─────────────┐
         │ DF  │ CA │        ICAO          │                  ME (payload)              │   CRC-24    │
         │5 bit│3bit│       24 bit         │                    56 bit                  │   24 bit    │
         └─────┴────┴──────────────────────┴────────────────────────────────────────────┴─────────────┘
 byte:    [ 0 ]      [   1   2   3  ]       [   4   5   6   7   8   9  10  ]            [ 11  12  13 ]
```

| Field | Bits | Meaning |
|---|---|---|
| **DF** | 1–5 | Downlink Format. ADS-B = 17. |
| **CA** | 6–8 | Capability subfield. |
| **ICAO** | 9–32 | 24-bit aircraft address (e.g. `A14104`). |
| **ME** | 33–88 | Payload. First 5 bits are the **Type Code**. |
| **CRC-24** | 89–112 | Parity. Generator polynomial **`0xFFF409`**. For DF=17 a valid message has CRC syndrome = 0 after running the standard 24-bit CRC over bits 1–88. |

The first byte is `(DF << 3) | CA`, so a DF=17 frame always starts with `0x88`–`0x8F`
(in the captured fixture, the long messages all begin with `*8d…` or `*8e…`).

### DF=17 Type Codes (the first 5 bits of ME)

| TC | Content | Used in this project? |
|---|---|---|
| 1–4 | Aircraft identification (callsign) | Yes |
| 5–8 | Surface position (taxi etc.) | Skipped |
| 9–18 | Airborne position with **barometric altitude** and **CPR-encoded lat/lon** | Yes |
| 19 | Airborne velocity (ground speed, track, vertical rate) | Yes |
| 20–22 | Airborne position with **GNSS altitude** | Optional |
| 23–31 | Other (reserved, status, target state, etc.) | Skipped |

### CPR (Compact Position Reporting), in 4 bullets

- Lat/lon are split into **even** and **odd** frames. One frame alone is ambiguous.
- **Globally unambiguous decoding** needs **one even + one odd frame within ~10 s**, then
  reconstructs lat/lon by comparing the two encodings (zone-index trick).
- **Locally unambiguous decoding** can use a known reference position (the receiver's
  location) and decode a single frame, valid within ~180 nm of the reference.
- Phase 1 implements **only the global (even/odd) variant** to keep the educational scope
  tight; locally-referenced decoding can be added later.

### CRC-24 in Mode S — the short version

- 24-bit CRC, generator polynomial `0xFFF409` (== `x^24 + x^23 + x^22 + ... + 1`).
- Computed bit-by-bit from MSB to LSB over the first 88 bits, then compared to the trailing
  24-bit CRC field.
- For DF=17 a clean message has **syndrome = 0**. The decoder rejects anything else (without
  CRC checking, noise produces ghost aircraft on the map).

## Status

**Phases 1 and 2 are complete.** Phase 3 (live RTL-SDR via TCP) is the next milestone.

- **Phase 1 — `:adsb-decoder`** — pure Kotlin/JVM library decoding Mode S DF=17 messages
  into structured `Aircraft` records. Covers CRC-24 verification, identification (TC 1–4),
  airborne position with CPR pair decoding (TC 9–18), and ground-speed velocity (TC 19).
  33 JUnit tests pass against both the canonical pyModeS sample and a real RTL-SDR
  capture from Tokyo (15 unique aircraft).
- **Phase 2 — `:app`** — Android app rendering aircraft on Google Maps. Replays the
  captured Mode S fixture through the Phase 1 decoder, draws Material flight markers
  rotated by track angle, per-aircraft polyline trails (max 100 points), and a
  `ModalBottomSheet` with full aircraft details on tap. Material 3 Dynamic Color follows
  the device wallpaper on Android 12+ and reacts to dark / light. Stale aircraft fall
  off the map after 60 seconds.

`MockMessageSource` (Phase 2) implements the same `Flow<String>` interface that the
Phase 3 TCP source will produce, so wiring the live source is a drop-in replacement
once the demodulator is ready.

## License

Not yet decided. Will be set to MIT or Apache 2.0 before public release.

## References

- *The 1090 Megahertz Riddle* (Junzi Sun) — primary reference for ADS-B / Mode S / CPR.
- [OpenSky Network `java-adsb`](https://github.com/openskynetwork/java-adsb) — Java ADS-B
  decoder, useful as a Kotlin reimplementation reference (Apache 2.0).
- [`junzis/pyModeS`](https://github.com/junzis/pyModeS) — Python implementation, useful for
  cross-checking algorithms.
- [`antirez/dump1090`](https://github.com/antirez/dump1090) /
  [`flightaware/dump1090`](https://github.com/flightaware/dump1090) — canonical C
  implementations.
- [`martinmarinov/rtl_tcp_andro-`](https://github.com/martinmarinov/rtl_tcp_andro-) —
  source of the SDR driver app and the Android `rtl_tcp` port.
