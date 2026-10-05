package com.fethica.downloadskit.sample.fixtures

/**
 * How the server misbehaves for one fixture. The default is a well-behaved server.
 *
 * @param delayFirstByteMs sleep this long before the first byte of the response (the
 *   status line), after the request has been read.
 * @param chunkSize write the body in chunks of this many bytes; 0 writes it in one go.
 * @param chunkDelayMs sleep this long after each chunk. With [chunkSize] this makes a
 *   slow transfer.
 * @param failWithStatus answer every request for the fixture with this status (404, 500,
 *   ...) and an empty body instead of the content.
 * @param cutAfterBytes close the socket after this many body bytes of the response,
 *   while the declared Content-Length promised more. Counted per response, from the
 *   start of the requested range.
 */
public data class Scenario(
    val delayFirstByteMs: Long = 0,
    val chunkSize: Int = 0,
    val chunkDelayMs: Long = 0,
    val failWithStatus: Int? = null,
    val cutAfterBytes: Long? = null,
) {
    init {
        require(delayFirstByteMs >= 0 && chunkSize >= 0 && chunkDelayMs >= 0)
        require(failWithStatus == null || failWithStatus in 400..599) { "failWithStatus must be 4xx or 5xx" }
        require(cutAfterBytes == null || cutAfterBytes >= 0)
    }

    public val isDefault: Boolean get() = this == NORMAL

    public companion object {
        public val NORMAL: Scenario = Scenario()
    }
}
