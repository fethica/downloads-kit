package com.fethica.downloadskit.sample.fixtures

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks raw HTTP over a socket rather than through HttpURLConnection, so what is
 * checked is exactly what the server put on the wire: status, headers, body length
 * and an early close.
 */
class FixtureServerTest {

    private lateinit var server: FixtureServer

    @Before
    fun setUp() {
        server = FixtureServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun bindsLoopbackOnAnEphemeralPort() {
        assertTrue(server.port in 1..65_535)
        assertTrue(server.baseUrl.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun fullGetReturnsTheFixtureBytes() {
        for (fixture in FixtureCatalog.all) {
            val response = request("GET", fixture.path)
            assertEquals(200, response.status)
            assertEquals(fixture.sizeBytes.toString(), response.headers["content-length"])
            assertEquals("bytes", response.headers["accept-ranges"])
            assertArrayEquals(fixture.bytes(), response.body)
            assertEquals(fixture.sha256, sha256Hex(response.body))
        }
    }

    @Test
    fun rangeRequestReturnsTheSliceWithItsHeaders() {
        val fixture = FixtureCatalog.medium
        val response = request("GET", fixture.path, "Range" to "bytes=1000-1999")
        assertEquals(206, response.status)
        assertEquals("bytes 1000-1999/${fixture.sizeBytes}", response.headers["content-range"])
        assertEquals("1000", response.headers["content-length"])
        assertArrayEquals(fixture.bytes().copyOfRange(1000, 2000), response.body)
    }

    @Test
    fun openEndedAndSuffixRanges() {
        val fixture = FixtureCatalog.small
        val size = fixture.sizeBytes
        val open = request("GET", fixture.path, "Range" to "bytes=${size - 10}-")
        assertEquals(206, open.status)
        assertEquals("bytes ${size - 10}-${size - 1}/$size", open.headers["content-range"])
        assertArrayEquals(fixture.bytes().copyOfRange(size - 10, size), open.body)

        val suffix = request("GET", fixture.path, "Range" to "bytes=-4")
        assertEquals(206, suffix.status)
        assertArrayEquals(fixture.bytes().copyOfRange(size - 4, size), suffix.body)
    }

    @Test
    fun unsatisfiableRangeIs416() {
        val fixture = FixtureCatalog.small
        val response = request("GET", fixture.path, "Range" to "bytes=${fixture.sizeBytes}-")
        assertEquals(416, response.status)
        assertEquals("bytes */${fixture.sizeBytes}", response.headers["content-range"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun headReturnsTheLengthWithoutABody() {
        val fixture = FixtureCatalog.large
        val response = request("HEAD", fixture.path)
        assertEquals(200, response.status)
        assertEquals(fixture.sizeBytes.toString(), response.headers["content-length"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun cutAfterBytesClosesEarly() {
        val fixture = FixtureCatalog.medium
        server.setScenario(fixture, Scenario(cutAfterBytes = 10_000))
        val response = request("GET", fixture.path)
        assertEquals(200, response.status)
        assertEquals(fixture.sizeBytes.toString(), response.headers["content-length"])
        assertEquals(10_000, response.body.size)
        assertArrayEquals(fixture.bytes().copyOf(10_000), response.body)
    }

    @Test
    fun failWithStatusReturnsTheStatus() {
        val fixture = FixtureCatalog.small
        server.setScenario(fixture, Scenario(failWithStatus = 404))
        assertEquals(404, request("GET", fixture.path).status)
        server.setScenario(fixture, Scenario(failWithStatus = 500))
        val response = request("GET", fixture.path)
        assertEquals(500, response.status)
        assertEquals(0, response.body.size)
        // Other fixtures are untouched.
        assertEquals(200, request("HEAD", FixtureCatalog.medium.path).status)
    }

    @Test
    fun resetRestoresNormalService() {
        val fixture = FixtureCatalog.small
        server.setScenario(fixture, Scenario(failWithStatus = 500, cutAfterBytes = 1))
        server.resetAll()
        val response = request("GET", fixture.path)
        assertEquals(200, response.status)
        assertArrayEquals(fixture.bytes(), response.body)
        assertEquals(1, server.requestCount(fixture))
    }

    @Test
    fun slowChunksAndFirstByteDelayStillDeliverEveryByte() {
        val fixture = FixtureCatalog.small
        server.setScenario(fixture, Scenario(delayFirstByteMs = 150, chunkSize = 16_384, chunkDelayMs = 20))
        val started = System.nanoTime()
        val response = request("GET", fixture.path)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, response.status)
        assertArrayEquals(fixture.bytes(), response.body)
        // 150 ms before the first byte plus 3 pauses between 4 chunks.
        assertTrue("took $elapsedMs ms", elapsedMs >= 150 + 3 * 20)
    }

    @Test
    fun unknownPathIs404AndOtherMethodsAre405() {
        assertEquals(404, request("GET", "/fixtures/nope.wav").status)
        assertEquals(405, request("POST", FixtureCatalog.small.path).status)
    }

    @Test
    fun parseRangeEdgeCases() {
        val r = FixtureServer.Companion
        assertEquals(FixtureServer.RangeResult.Slice(0, 9), r.parseRange("bytes=0-9", 100))
        assertEquals(FixtureServer.RangeResult.Slice(90, 99), r.parseRange("bytes=90-500", 100))
        assertEquals(FixtureServer.RangeResult.Ignore, r.parseRange("bytes=0-1,5-6", 100))
        assertEquals(FixtureServer.RangeResult.Ignore, r.parseRange("items=0-1", 100))
        assertEquals(FixtureServer.RangeResult.Ignore, r.parseRange("bytes=9-1", 100))
        assertEquals(FixtureServer.RangeResult.Unsatisfiable, r.parseRange("bytes=100-", 100))
        assertEquals(FixtureServer.RangeResult.Unsatisfiable, r.parseRange("bytes=-0", 100))
    }

    // ---------------------------------------------------------------- raw client

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(method: String, path: String, vararg headers: Pair<String, String>): Response {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", server.port), 2_000)
            socket.soTimeout = 5_000
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                for ((k, v) in headers) append("$k: $v\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); flush() }
            val input = socket.getInputStream()
            val statusLine = readLine(input)
            val status = statusLine.split(' ')[1].toInt()
            val parsed = HashMap<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                parsed[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            // Read to EOF: the server always closes, which is also how a cut shows.
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                body.write(buffer, 0, n)
            }
            return Response(status, parsed, body.toByteArray())
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            check(b != -1) { "unexpected end of headers" }
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
