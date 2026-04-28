package com.ayakix.pocketradar.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP client for the `rtl_tcp` protocol. Connects to a server (the Android
 * "SDR driver" app on `localhost:1234`, or the upstream `librtlsdr` `rtl_tcp`
 * binary), reads the 12-byte hello header, sends control commands, and exposes
 * the I/Q sample stream as a [Flow].
 *
 * Thread model: [connect], the setter functions, and the underlying socket I/O
 * all run on [Dispatchers.IO]. The class is **not** thread-safe — wrap in a
 * higher-level component if multiple producers are sending commands.
 *
 * Wire format reference: [RtlTcpProtocol].
 */
class RtlTcpClient(
    private val host: String = "localhost",
    private val port: Int = RtlTcpProtocol.DEFAULT_PORT,
    /** Buffer size for each I/Q chunk emitted from [samples]. */
    private val readBufferSize: Int = 16 * 1024,
    /** TCP connect timeout in milliseconds. */
    private val connectTimeoutMillis: Int = 5_000,
    /**
     * Socket read timeout (ms). Set to a non-zero value so that
     * `InputStream.read()` returns periodically and the [samples] flow can
     * observe coroutine cancellation. Default: 1 s.
     */
    private val readPollTimeoutMillis: Int = 1_000,
) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    /** Server header information. `null` until [connect] succeeds. */
    var serverInfo: ServerInfo? = null
        private set

    /**
     * Open the TCP connection and parse the 12-byte hello header. Must be
     * called before any of the setters or [samples].
     */
    suspend fun connect(): ServerInfo = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            s.tcpNoDelay = true
            s.soTimeout = readPollTimeoutMillis

            val inp = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())

            // --- Read the 12-byte header ---
            val magic = ByteArray(4)
            inp.readFully(magic)
            val magicAscii = String(magic, Charsets.US_ASCII)
            require(magicAscii == RtlTcpProtocol.MAGIC) {
                "Bad rtl_tcp magic: expected '${RtlTcpProtocol.MAGIC}', got '$magicAscii'"
            }
            val tunerType = inp.readInt()
            val gainStages = inp.readInt()

            socket = s
            input = inp
            output = out
            ServerInfo(tunerType, gainStages).also { serverInfo = it }
        } catch (t: Throwable) {
            // Don't leak the OS socket if anything between connect() and the
            // header parse fails (bad magic, EOF, timeout, etc.).
            runCatching { s.close() }
            throw t
        }
    }

    /** Apply the standard ADS-B tuning (1090 MHz, 2.4 MS/s, manual gain). */
    suspend fun applyAdsbDefaults() {
        setSampleRate(RtlTcpProtocol.ADSB_SAMPLE_RATE_HZ)
        setFrequency(RtlTcpProtocol.ADSB_FREQUENCY_HZ)
        setAgcMode(false)
        setGainMode(manual = true)
        setTunerGain(RtlTcpProtocol.ADSB_TUNER_GAIN_TENTHS_DB)
    }

    suspend fun setFrequency(hz: Int) =
        sendCommand(RtlTcpProtocol.CMD_SET_FREQUENCY, hz)

    suspend fun setSampleRate(hz: Int) =
        sendCommand(RtlTcpProtocol.CMD_SET_SAMPLE_RATE, hz)

    suspend fun setGainMode(manual: Boolean) =
        sendCommand(RtlTcpProtocol.CMD_SET_GAIN_MODE, if (manual) 1 else 0)

    suspend fun setTunerGain(tenthsOfDb: Int) =
        sendCommand(RtlTcpProtocol.CMD_SET_TUNER_GAIN, tenthsOfDb)

    suspend fun setAgcMode(on: Boolean) =
        sendCommand(RtlTcpProtocol.CMD_SET_AGC_MODE, if (on) 1 else 0)

    /**
     * I/Q sample stream. Each emitted [ByteArray] is a slice of u8 I, u8 Q
     * pairs. The flow runs on [Dispatchers.IO] and completes when the server
     * closes the socket.
     *
     * Cancellation: `InputStream.read()` is a JVM blocking call that does not
     * observe Kotlin coroutine cancellation directly. We use the socket's
     * `soTimeout` (configured at [connect]) to wake the read every
     * [readPollTimeoutMillis], so cancellation takes effect within that window.
     * For a hard cancel, also call [close] from outside the collector — that
     * will close the socket and unblock any in-progress read with a
     * `SocketException` immediately.
     */
    fun samples(): Flow<ByteArray> = flow {
        val inp = checkNotNull(input) { "Not connected; call connect() first" }
        val buffer = ByteArray(readBufferSize)
        while (currentCoroutineContext().isActive) {
            val read = try {
                inp.read(buffer)
            } catch (_: SocketTimeoutException) {
                // soTimeout fired; loop back so cancellation can be observed.
                continue
            }
            if (read < 0) break // server closed
            // Defensive copy: callers must not see a buffer that mutates beneath them.
            emit(buffer.copyOfRange(0, read))
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        try {
            socket?.close()
        } finally {
            socket = null
            input = null
            output = null
        }
    }

    private suspend fun sendCommand(code: Byte, param: Int) = withContext(Dispatchers.IO) {
        val out = checkNotNull(output) { "Not connected; call connect() first" }
        val packet = ByteBuffer.allocate(RtlTcpProtocol.COMMAND_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(code)
            .putInt(param)
            .array()
        out.write(packet)
        out.flush()
    }
}
