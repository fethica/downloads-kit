package com.fethica.downloadskit.sample.fixtures

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 that serves [fixtures] by their [WavFixture.path].
 *
 * Supports GET and HEAD, `Content-Length`, `Accept-Ranges: bytes` and a single
 * `Range: bytes=` request (206 with `Content-Range`; 416 when it cannot be satisfied;
 * a multi-range request is answered with the whole file, as RFC 9110 allows). Every
 * response closes the connection, which keeps the server small and every request
 * independent. Per-fixture [Scenario]s can be changed at any time from any thread and
 * apply to the next request.
 *
 * It binds the loopback address only and is meant for the sample and its tests, never for
 * anything reachable from another machine.
 */
public class FixtureServer(
    fixtures: List<WavFixture> = FixtureCatalog.all,
) : Closeable {

    private val byPath: Map<String, WavFixture> = fixtures.associateBy { it.path }
    private val scenarios = ConcurrentHashMap<String, Scenario>()
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val connectionIds = AtomicInteger()

    /** Bound port, or -1 before [start] and after [close]. */
    public val port: Int get() = socket?.takeUnless { it.isClosed }?.localPort ?: -1

    public val isRunning: Boolean get() = port != -1

    /** `http://127.0.0.1:<port>`. Throws before [start]. */
    public val baseUrl: String
        get() {
            check(isRunning) { "the fixture server is not running" }
            return "http://$HOST:$port"
        }

    /** The URL of [fixture] on this server. */
    public fun urlFor(fixture: WavFixture): String = baseUrl + fixture.path

    /**
     * Binds 127.0.0.1 and starts accepting.
     *
     * @param preferredPort a port to try first (the sample reuses its last one so URLs, and
     *   therefore Media3 cache keys, stay the same across launches). 0, the default,
     *   takes an ephemeral port; a busy preferred port falls back to an ephemeral one.
     * @return the bound port.
     */
    @Synchronized
    public fun start(preferredPort: Int = 0): Int {
        socket?.let { if (!it.isClosed) return it.localPort }
        val server = bind(preferredPort) ?: bind(0) ?: throw IOException("could not bind $HOST")
        socket = server
        acceptThread = Thread({ acceptLoop(server) }, "fixture-server-accept").apply {
            isDaemon = true
            start()
        }
        return server.localPort
    }

    private fun bind(port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(HOST), port), BACKLOG)
        }
    } catch (_: IOException) {
        null
    }

    /** Stops accepting. Connections already being served finish on their own threads. */
    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        socket = null
        acceptThread = null
    }

    // ---------------------------------------------------------------- scenarios

    public fun scenario(fixture: WavFixture): Scenario = scenarios[fixture.name] ?: Scenario.NORMAL

    public fun setScenario(fixture: WavFixture, scenario: Scenario) {
        if (scenario.isDefault) scenarios.remove(fixture.name) else scenarios[fixture.name] = scenario
    }

    /** Applies [change] to the current scenario of [fixture]. */
    public fun updateScenario(fixture: WavFixture, change: (Scenario) -> Scenario) {
        setScenario(fixture, change(scenario(fixture)))
    }

    /** Back to normal for [fixture]. */
    public fun reset(fixture: WavFixture) {
        scenarios.remove(fixture.name)
    }

    /** Back to normal for every fixture, and request counters to zero. */
    public fun resetAll() {
        scenarios.clear()
        requestCounts.clear()
    }

    /** Requests received for [fixture] since start or the last [resetAll]. */
    public fun requestCount(fixture: WavFixture): Int = requestCounts[fixture.name]?.get() ?: 0

    // ---------------------------------------------------------------- serving

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break // closed
            } catch (_: Exception) {
                continue
            }
            Thread({ serve(client) }, "fixture-server-${connectionIds.incrementAndGet()}").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
                respond(request, socket.getOutputStream())
            }
        } catch (_: Exception) {
            // The client went away, a scenario cut the response, or the sleep of a slow
            // scenario was interrupted. Never rethrown: inside an app an uncaught
            // exception on this thread would take the whole process down.
        }
    }

    private fun respond(request: Request, out: OutputStream) {
        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(out, 405, "Method Not Allowed", listOf("Allow" to "GET, HEAD", "Content-Length" to "0"))
            return
        }
        val fixture = byPath[request.path.substringBefore('?')]
        if (fixture == null) {
            writeHead(out, 404, "Not Found", listOf("Content-Length" to "0"))
            return
        }
        requestCounts.getOrPut(fixture.name) { AtomicInteger() }.incrementAndGet()
        val scenario = scenario(fixture)
        if (scenario.delayFirstByteMs > 0) Thread.sleep(scenario.delayFirstByteMs)

        scenario.failWithStatus?.let { status ->
            writeHead(out, status, reasonPhrase(status), listOf("Content-Length" to "0"))
            return
        }

        val body = fixture.cached
        val size = body.size.toLong()
        val common = listOf("Content-Type" to CONTENT_TYPE, "Accept-Ranges" to "bytes")
        // Range applies to GET only; any other method ignores it and describes the whole
        // representation (RFC 9110, section 14.2). HEAD therefore answers as a full GET.
        val range = if (request.method == "GET") request.headers["range"]?.let { parseRange(it, size) } else null
        val (start, endInclusive) = when (range) {
            null, RangeResult.Ignore -> {
                writeHead(out, 200, "OK", common + ("Content-Length" to size.toString()))
                0L to size - 1
            }
            RangeResult.Unsatisfiable -> {
                writeHead(out, 416, "Range Not Satisfiable", common + listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                return
            }
            is RangeResult.Slice -> {
                val length = range.endInclusive - range.start + 1
                writeHead(
                    out,
                    206,
                    "Partial Content",
                    common + listOf(
                        "Content-Range" to "bytes ${range.start}-${range.endInclusive}/$size",
                        "Content-Length" to length.toString(),
                    ),
                )
                range.start to range.endInclusive
            }
        }
        if (request.method == "HEAD") return
        writeBody(out, body, start, endInclusive, scenario)
    }

    private fun writeBody(out: OutputStream, body: ByteArray, start: Long, endInclusive: Long, scenario: Scenario) {
        val total = endInclusive - start + 1
        val limit = scenario.cutAfterBytes?.coerceAtMost(total) ?: total
        val chunk = if (scenario.chunkSize > 0) scenario.chunkSize.toLong() else limit.coerceAtLeast(1)
        var written = 0L
        while (written < limit) {
            val n = minOf(chunk, limit - written).toInt()
            out.write(body, (start + written).toInt(), n)
            out.flush()
            written += n
            if (scenario.chunkDelayMs > 0 && written < limit) Thread.sleep(scenario.chunkDelayMs)
        }
        out.flush()
        // When the cut is shorter than the body, returning here closes the socket with
        // fewer bytes than Content-Length promised: an interrupted transfer.
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, headers: List<Pair<String, String>>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    /** Reads the request line and headers; the server never needs a request body. */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) return null
        }
        return Request(parts[0].uppercase(), parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return null
        }
    }

    internal sealed interface RangeResult {
        data object Ignore : RangeResult
        data object Unsatisfiable : RangeResult
        data class Slice(val start: Long, val endInclusive: Long) : RangeResult
    }

    internal companion object {
        const val HOST = "127.0.0.1"
        const val CONTENT_TYPE = "audio/wav"
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_LINE = 8_192
        private const val MAX_HEADERS = 100

        /**
         * One `bytes=` range against a body of [size] bytes. Anything malformed, any other
         * unit and any multi-range request is ignored (the whole body is served).
         */
        fun parseRange(header: String, size: Long): RangeResult {
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return RangeResult.Ignore
            val spec = value.substring(6).trim()
            if (spec.contains(',')) return RangeResult.Ignore
            val dash = spec.indexOf('-')
            if (dash < 0) return RangeResult.Ignore
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (first.isEmpty()) {
                // Suffix range: the last N bytes.
                val n = last.toLongOrNull() ?: return RangeResult.Ignore
                if (n <= 0 || size == 0L) return RangeResult.Unsatisfiable
                return RangeResult.Slice(maxOf(0, size - n), size - 1)
            }
            val start = first.toLongOrNull() ?: return RangeResult.Ignore
            val end = if (last.isEmpty()) null else last.toLongOrNull() ?: return RangeResult.Ignore
            if (start < 0 || (end != null && end < start)) return RangeResult.Ignore
            // Checked before clamping the end: "bytes=<size>-" is unsatisfiable, not invalid.
            if (start >= size) return RangeResult.Unsatisfiable
            return RangeResult.Slice(start, minOf(end ?: (size - 1), size - 1))
        }

        fun reasonPhrase(status: Int): String = when (status) {
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            410 -> "Gone"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}
