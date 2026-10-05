package com.fethica.downloadskit

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The exception media3 hands back on a failure, turned into something analytics can bucket. */
class DownloadFailureReasonTest {

    @Test
    fun `no exception is unknown`() {
        assertEquals(DownloadFailureReason.UNKNOWN, classifyFailure(null))
    }

    @Test
    fun `dns and socket problems are network failures`() {
        assertEquals(DownloadFailureReason.NETWORK, classifyFailure(UnknownHostException("nope")))
        assertEquals(DownloadFailureReason.NETWORK, classifyFailure(SocketTimeoutException()))
        assertEquals(DownloadFailureReason.NETWORK, classifyFailure(ConnectException()))
    }

    @Test
    fun `a truncated body is a network failure`() {
        assertEquals(DownloadFailureReason.NETWORK, classifyFailure(EOFException()))
    }

    @Test
    fun `a full disk is its own bucket so the UI can say something useful`() {
        assertEquals(DownloadFailureReason.STORAGE_FULL, classifyFailure(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `a bad response code is an http failure`() {
        class InvalidResponseCodeException(message: String) : IOException(message)
        assertEquals(DownloadFailureReason.HTTP, classifyFailure(InvalidResponseCodeException("Response code: 404")))
    }

    @Test
    fun `the cause is inspected when the top level exception says nothing`() {
        assertEquals(DownloadFailureReason.NETWORK, classifyFailure(RuntimeException("wrapped", UnknownHostException())))
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(DownloadFailureReason.UNKNOWN, classifyFailure(IllegalStateException("what")))
    }

    /**
     * media3's HTTP stack wraps EVERYTHING it cannot finish in one exception —
     * a DNS miss, a read timeout and a 404 alike — so the wrapper is not the
     * answer. These are the real class, taken from the media3 this module builds
     * against, not a stand-in named to look like it.
     */
    @Test
    fun `a media3 http wrapper around a dns failure is a network failure`() {
        val wrapped = httpFailure(UnknownHostException("host.example"))
        assertEquals("HttpDataSourceException", wrapped.javaClass.simpleName)
        assertEquals(DownloadFailureReason.NETWORK, classifyFailure(wrapped))
    }

    @Test
    fun `a media3 http wrapper around a timeout is a network failure`() {
        assertEquals(DownloadFailureReason.NETWORK, classifyFailure(httpFailure(SocketTimeoutException())))
    }

    @Test
    fun `a media3 http wrapper with nothing under it is still an http failure`() {
        assertEquals(DownloadFailureReason.HTTP, classifyFailure(bareHttpFailure()))
    }

    @Test
    fun `a response code still answers for itself through the wrapper`() {
        // InvalidResponseCodeException IS an HttpDataSourceException, and unlike the
        // bare wrapper it says something: a code is an HTTP failure, full stop.
        class InvalidResponseCodeException(message: String) : IOException(message)
        assertEquals(
            DownloadFailureReason.HTTP,
            classifyFailure(InvalidResponseCodeException("Response code: 503")),
        )
    }

    @Test
    fun `a full disk under an http wrapper is still a full disk`() {
        val wrapped = httpFailure(IOException("write failed: ENOSPC (No space left on device)"))
        assertEquals(DownloadFailureReason.STORAGE_FULL, classifyFailure(wrapped))
    }

    @Test
    fun `every reason has a stable analytics key`() {
        assertEquals("unknown", DownloadFailureReason.UNKNOWN.key)
        assertEquals("network", DownloadFailureReason.NETWORK.key)
        assertEquals("http", DownloadFailureReason.HTTP.key)
        assertEquals("storage_full", DownloadFailureReason.STORAGE_FULL.key)
    }

    private companion object {
        /**
         * A REAL media3 `HttpDataSourceException`, built through its own constructor.
         *
         * The bucketing keys on the class NAME — it has to, the rule is a pure function
         * with no media3 loaded — so a stand-in class named to look like it would prove
         * nothing about the exception media3 actually throws. Reflection is what gets one
         * built here: the constructor stores the `DataSpec` it failed on and reads
         * nothing from it, and building a real spec would need an `android.net.Uri`,
         * which is a stub in a JVM test.
         */
        fun httpFailure(cause: IOException): IOException =
            HttpDataSource.HttpDataSourceException::class.java
                .getDeclaredConstructor(
                    IOException::class.java,
                    DataSpec::class.java,
                    Int::class.javaPrimitiveType,
                )
                .newInstance(cause, null, HttpDataSource.HttpDataSourceException.TYPE_OPEN)

        fun bareHttpFailure(): IOException =
            HttpDataSource.HttpDataSourceException::class.java
                .getDeclaredConstructor(
                    String::class.java,
                    DataSpec::class.java,
                    Int::class.javaPrimitiveType,
                )
                .newInstance("no body", null, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
    }
}
