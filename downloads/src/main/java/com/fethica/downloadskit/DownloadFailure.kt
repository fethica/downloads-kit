package com.fethica.downloadskit

import java.io.IOException
import java.io.InterruptedIOException

/**
 * Why a download failed, bucketed so a host can send it to analytics as a low
 * cardinality property and show a useful message.
 *
 * @property key the stable string to report; never renamed once shipped.
 */
public enum class DownloadFailureReason(public val key: String) {
    UNKNOWN("unknown"),
    NETWORK("network"),
    HTTP("http"),
    STORAGE_FULL("storage_full"),
}

/**
 * Walks the cause chain of the exception media3 reports and buckets it.
 *
 * `HttpDataSourceException` is the one wrapper that must not answer on its own:
 * media3's HTTP stack wraps a DNS failure and a read timeout in it exactly as it
 * wraps a real protocol error, so treating the wrapper as the answer reported
 * "Not available" and an `http` failure for an ordinary dropped connection. It is
 * remembered as the fallback instead and its cause is given its say first.
 */
internal fun classifyFailure(error: Throwable?): DownloadFailureReason {
    var current: Throwable? = error
    var depth = 0
    var sawHttpWrapper = false
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current.isHttpWrapper()) {
            sawHttpWrapper = true
        } else {
            classifyOne(current)?.let { return it }
        }
        current = current.cause.takeIf { it !== current }
        depth++
    }
    return if (sawHttpWrapper) DownloadFailureReason.HTTP else DownloadFailureReason.UNKNOWN
}

/**
 * The bare media3 HTTP wrapper, which says nothing by itself.
 *
 * Matched by name for the same reason every other rule here is: the bucket has to
 * be decidable from a JVM test, with no media3 class loaded. `InvalidResponseCode`
 * is a subclass and DOES say something — a response code is an HTTP failure and
 * nothing else — so it is left to [classifyOne].
 */
private fun Throwable.isHttpWrapper(): Boolean {
    val name = javaClass.simpleName
    return name.contains("HttpDataSourceException") && !name.contains("InvalidResponseCode")
}

private fun classifyOne(error: Throwable): DownloadFailureReason? {
    val name = error.javaClass.simpleName
    val message = error.message.orEmpty()
    return when {
        message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true) ->
            DownloadFailureReason.STORAGE_FULL
        name.contains("InvalidResponseCode") -> DownloadFailureReason.HTTP
        error is java.net.UnknownHostException ||
            error is java.net.SocketException ||
            error is InterruptedIOException ||
            error is javax.net.ssl.SSLException ->
            DownloadFailureReason.NETWORK
        error is IOException -> DownloadFailureReason.NETWORK
        else -> null
    }
}

private const val MAX_CAUSE_DEPTH = 8
