package com.ayakix.pocketradar.radio

/**
 * Constants and command codes for the rtl_tcp wire protocol shared by the upstream
 * `librtlsdr` server and Martin Marinov's Android port (`marto.rtl_tcp_andro`).
 *
 * Protocol summary:
 *   - On connect, the server sends a **12-byte big-endian header**:
 *       4 bytes: ASCII magic "RTL0"
 *       4 bytes: tuner type (e.g. 5 = R820T, 6 = R828D)
 *       4 bytes: number of tuner gain stages
 *   - After the header, the server streams **u8 I, u8 Q** samples interleaved
 *     continuously until the connection is closed.
 *   - The client may send **5-byte commands** at any time:
 *       1 byte:  command code
 *       4 bytes: parameter (big-endian uint32)
 */
object RtlTcpProtocol {

    /**
     * Default rtl_tcp listening port. Note: librtlsdr's upstream `rtl_tcp`
     * binary uses **1234**, but Martin Marinov's Android port (the "SDR
     * driver" app, package `marto.rtl_tcp_andro`) defaults to **14423** to
     * avoid collisions with other Android apps. Since PocketRadar is
     * primarily paired with the Android driver, we follow its default so
     * users can run the driver app without editing its launch arguments.
     * Override via the [RtlTcpClient] / [RtlTcpMessageSource] constructor
     * when targeting the upstream binary.
     */
    const val DEFAULT_PORT: Int = 14423

    /** Size of the server's hello header in bytes. */
    const val HEADER_SIZE: Int = 12

    /** ASCII magic at the start of the header. */
    const val MAGIC: String = "RTL0"

    /** Size of a client command in bytes. */
    const val COMMAND_SIZE: Int = 5

    // --- Command codes ----------------------------------------------------------
    // Subset relevant to ADS-B; the full list lives in librtlsdr's `rtl_tcp.c`.

    /** Set the centre frequency in Hz. ADS-B uses 1090000000. */
    const val CMD_SET_FREQUENCY: Byte = 0x01

    /** Set the sample rate in Hz. 2.4 MHz is the standard ADS-B rate. */
    const val CMD_SET_SAMPLE_RATE: Byte = 0x02

    /** Set tuner gain mode. Param 0 = automatic, 1 = manual. */
    const val CMD_SET_GAIN_MODE: Byte = 0x03

    /** Set tuner gain in tenths of a dB (e.g. 490 = 49 dB). */
    const val CMD_SET_TUNER_GAIN: Byte = 0x04

    /** Enable/disable AGC. Param 0 = off, 1 = on. */
    const val CMD_SET_AGC_MODE: Byte = 0x08

    // --- ADS-B suggested defaults ----------------------------------------------

    /** ADS-B downlink frequency in Hz (1090 MHz). */
    const val ADSB_FREQUENCY_HZ: Int = 1_090_000_000

    /**
     * Sample rate dump1090 / pyModeS use for ADS-B (2.4 MS/s).
     * 2.0 MS/s also works; 2.4 gives slightly better preamble margin.
     */
    const val ADSB_SAMPLE_RATE_HZ: Int = 2_400_000

    /** Typical R820T/R828D maximum gain in tenths of a dB. */
    const val ADSB_TUNER_GAIN_TENTHS_DB: Int = 490
}
