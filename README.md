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
- Android 14 or later (required for the Foreground Service type used during reception).
- Google Play services with the Maps API key configured.
- The **SDR driver** app installed and bound to the dongle:
  - Google Play: <https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro>
  - Package name: `marto.rtl_tcp_andro` (formerly known as "RTL2832U Driver")
  - Source: <https://github.com/martinmarinov/rtl_tcp_andro->
  - On first connection, accept the USB permission dialog and choose to open the dongle
    with this app by default.

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
- Android Studio (latest stable) with Kotlin 2.x.
- Jetpack Compose, Material 3.
- Coroutines and Flow (StateFlow / SharedFlow) for concurrency.
- Koin for dependency injection.
- Google Maps SDK for Android.
- API key managed via `secrets-gradle-plugin`; put `MAPS_API_KEY=...` in
  `local.properties` (gitignored).

## Development phases

The project is implemented **back-to-front** so that each phase produces a working,
testable artifact.

| Phase | Scope | Hardware required |
|---|---|---|
| **Phase 1** | Mode S hex → `Aircraft` decoder. CRC-24, DF=17 parser, Type Code branches, CPR pair handling. JUnit-driven, runs on the JVM. | None |
| **Phase 2** | UI + Google Maps. Replays a captured-message fixture (from `dump1090 --raw`) through the Phase 1 decoder and renders aircraft on the map. Runs on emulator or device. | None (uses captured fixture) |
| **Phase 3** | Real-hardware integration. TCP connection to the **SDR driver** app's `rtl_tcp` server, I/Q stream → Mode S frames in Kotlin (preamble detection, PPM demodulation), wired into the Phase 1 decoder and Phase 2 UI for end-to-end live tracking. | Android device + RTL-SDR + antenna |

The detailed task breakdown lives in `plan.md`.

## Status

Currently in **planning stage**. Hardware has been verified on macOS. The Android
implementation has not started yet. This README will grow as TODOs are completed.

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
