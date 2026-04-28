package com.ayakix.pocketradar.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests that spin up a tiny in-process rtl_tcp-compatible server on a
 * random localhost port, then exercise the client against it.
 */
class RtlTcpClientTest {

    private val servers = mutableListOf<FakeServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    @Test
    fun `connect parses the 12-byte hello header`() = runBlocking {
        val server = startServer { client ->
            client.writeHelloHeader(tunerType = 6, gainStages = 29) // R828D, 29 stages
            // Hold the connection open briefly so the client can read.
            // Block until the client closes the socket (read() returns -1).
            // More reliable than a fixed sleep on slow CI runners.
            client.getInputStream().read()
        }

        RtlTcpClient(host = "localhost", port = server.port).use { rtl ->
            val info = rtl.connect()
            assertEquals(6, info.tunerType)
            assertEquals("R828D", info.tunerName)
            assertEquals(29, info.gainStageCount)
        }
    }

    @Test
    fun `connect rejects a header with the wrong magic`() {
        val server = startServer { client ->
            // Wrong magic: "XXXX" instead of "RTL0".
            val out = DataOutputStream(client.getOutputStream())
            out.write("XXXX".toByteArray(Charsets.US_ASCII))
            out.writeInt(5)
            out.writeInt(29)
            out.flush()
            // Block until the client closes the socket (read() returns -1).
            // More reliable than a fixed sleep on slow CI runners.
            client.getInputStream().read()
        }

        val client = RtlTcpClient(host = "localhost", port = server.port)
        try {
            assertFailsWith<IllegalArgumentException> {
                runBlocking { client.connect() }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `setFrequency sends the 5-byte big-endian command`() = runBlocking {
        val received = ByteArray(RtlTcpProtocol.COMMAND_SIZE)
        val captured = CountDownLatch(1)

        val server = startServer { client ->
            client.writeHelloHeader(tunerType = 5, gainStages = 29)
            val inp = DataInputStream(client.getInputStream())
            inp.readFully(received)
            captured.countDown()
            // Block until the client closes the socket (read() returns -1).
            // More reliable than a fixed sleep on slow CI runners.
            client.getInputStream().read()
        }

        RtlTcpClient(host = "localhost", port = server.port).use { rtl ->
            rtl.connect()
            rtl.setFrequency(RtlTcpProtocol.ADSB_FREQUENCY_HZ)
        }

        assertTrueOrFail("server did not receive the command") {
            captured.await(1, TimeUnit.SECONDS)
        }

        // 0x01 + big-endian 1_090_000_000 (== 0x40F81480)
        assertContentEquals(
            byteArrayOf(0x01, 0x40, 0xF8.toByte(), 0x14, 0x80.toByte()),
            received,
        )
    }

    @Test
    fun `applyAdsbDefaults sends the expected sequence of commands`() = runBlocking {
        val expectedSize = RtlTcpProtocol.COMMAND_SIZE * 5 // 5 commands
        val received = ByteArray(expectedSize)
        val captured = CountDownLatch(1)

        val server = startServer { client ->
            client.writeHelloHeader(tunerType = 6, gainStages = 29)
            val inp = DataInputStream(client.getInputStream())
            inp.readFully(received)
            captured.countDown()
            // Block until the client closes the socket (read() returns -1).
            // More reliable than a fixed sleep on slow CI runners.
            client.getInputStream().read()
        }

        RtlTcpClient(host = "localhost", port = server.port).use { rtl ->
            rtl.connect()
            rtl.applyAdsbDefaults()
        }

        assertTrueOrFail("server did not receive all five commands") {
            captured.await(1, TimeUnit.SECONDS)
        }

        // applyAdsbDefaults() order: sample rate -> frequency -> AGC off -> gain manual -> gain value
        assertEquals(0x02, received[0])  // CMD_SET_SAMPLE_RATE
        assertEquals(0x01, received[5])  // CMD_SET_FREQUENCY
        assertEquals(0x08, received[10]) // CMD_SET_AGC_MODE
        assertEquals(0x03, received[15]) // CMD_SET_GAIN_MODE
        assertEquals(0x04, received[20]) // CMD_SET_TUNER_GAIN
    }

    @Test
    fun `samples flow streams I-Q bytes after the header`() = runBlocking {
        val payload = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), // I=128, Q=128
            0x81.toByte(), 0x7F.toByte(), // I=129, Q=127
            0x40.toByte(), 0xC0.toByte(), // I=64,  Q=192
        )

        val server = startServer { client ->
            client.writeHelloHeader(tunerType = 6, gainStages = 29)
            val out = client.getOutputStream()
            out.write(payload)
            out.flush()
            // Handler returns immediately so FakeServer.use { ... } closes the
            // socket; the client's flow then sees EOF and toList() completes.
            // (Don't try to wait for the client to close first — that deadlocks
            // because the client is waiting for *our* close to end the flow.)
        }

        val collected = withContext(Dispatchers.IO) {
            RtlTcpClient(
                host = "localhost",
                port = server.port,
                readBufferSize = 4,
            ).use { rtl ->
                rtl.connect()
                rtl.samples().toList().fold(ByteArray(0)) { acc, chunk -> acc + chunk }
            }
        }

        assertContentEquals(payload, collected)
    }

    // ---- Helpers --------------------------------------------------------------

    private fun startServer(handler: (java.net.Socket) -> Unit): FakeServer {
        val server = FakeServer(handler)
        servers += server
        return server
    }

    private fun assertTrueOrFail(message: String, predicate: () -> Boolean) {
        if (!predicate()) throw AssertionError(message)
    }
}

/**
 * Tiny one-shot rtl_tcp-compatible server: accepts a single client, runs [handler]
 * on a background thread, then closes. Bound to a random localhost port.
 */
private class FakeServer(handler: (java.net.Socket) -> Unit) : Closeable {

    private val server = ServerSocket(0)

    val port: Int get() = server.localPort

    private val acceptThread = thread(name = "FakeRtlTcpServer", isDaemon = true) {
        try {
            server.accept().use { client -> handler(client) }
        } catch (_: SocketException) {
            // Expected when close() races the accept loop.
        }
    }

    override fun close() {
        runCatching { server.close() }
        acceptThread.join(500)
    }
}

/** Convenience: write the 12-byte rtl_tcp hello header onto a socket. */
private fun java.net.Socket.writeHelloHeader(tunerType: Int, gainStages: Int) {
    val out = DataOutputStream(getOutputStream())
    out.write(RtlTcpProtocol.MAGIC.toByteArray(Charsets.US_ASCII))
    out.writeInt(tunerType)
    out.writeInt(gainStages)
    out.flush()
}
