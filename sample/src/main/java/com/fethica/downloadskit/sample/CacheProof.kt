package com.fethica.downloadskit.sample

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import com.fethica.downloadskit.MediaDownloads
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves a completed download is whole and readable offline WITHOUT touching the cache's
 * span files: the bytes are read back through the downloader's own read-only factory,
 * [MediaDownloads.cacheDataSourceFactory], exactly as a player would read them.
 *
 * What makes it a proof that the read came from the cache:
 *
 * 1. Before reading, Media3's cache index must report the whole resource present:
 *    `Cache.isCached(key, 0, size)` true, `Cache.getCachedBytes(key, 0, size) == size`,
 *    and the content length the downloader recorded in the key's `ContentMetadata` equal
 *    to the fixture size.
 * 2. The upstream handed to the factory is [NeverUpstream]: opening it counts the open
 *    and throws. `CacheDataSource` only opens its upstream for a range the cache does not
 *    hold, so a read that returns every byte with zero upstream opens was served by the
 *    cache alone. Any hole would fail the read instead of quietly fetching it.
 * 3. The bytes read hash to the fixture's SHA-256 and their count equals its size.
 *
 * The cache key is the request URL (the library sets no custom cache key), which is why
 * the sample remembers the exact URL each item was requested with.
 */
@OptIn(UnstableApi::class)
internal object CacheProof {

    data class Result(
        val key: String,
        val expectedBytes: Long,
        val recordedLength: Long,
        val cachedBytesBefore: Long,
        val fullyCachedBefore: Boolean,
        val bytesRead: Long,
        val sha256: String?,
        val upstreamOpens: Int,
        val fullyCachedAfter: Boolean,
        val error: String?,
    ) {
        /** Every condition of the proof held; the SHA-256 is compared by the caller. */
        val servedFromCacheOnly: Boolean
            get() = error == null &&
                fullyCachedBefore &&
                fullyCachedAfter &&
                cachedBytesBefore == expectedBytes &&
                recordedLength == expectedBytes &&
                bytesRead == expectedBytes &&
                upstreamOpens == 0
    }

    /** Blocking; call it off the main thread. */
    fun verify(downloads: MediaDownloads, key: String, expectedBytes: Long): Result {
        val cache = downloads.downloadCache
        val recordedLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val cachedBefore = cache.getCachedBytes(key, 0, expectedBytes)
        val fullyCachedBefore = cache.isCached(key, 0, expectedBytes)

        val upstream = NeverUpstream()
        val source = downloads.cacheDataSourceFactory(upstream).createDataSource()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var error: String? = null
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse(key)).build())
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                digest.update(buffer, 0, n)
                total += n
            }
        } catch (e: IOException) {
            error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        } finally {
            runCatching { source.close() }
        }
        val sha = if (error == null) digest.digest().joinToString("") { "%02x".format(it) } else null
        return Result(
            key = key,
            expectedBytes = expectedBytes,
            recordedLength = recordedLength,
            cachedBytesBefore = cachedBefore,
            fullyCachedBefore = fullyCachedBefore,
            bytesRead = total,
            sha256 = sha,
            upstreamOpens = upstream.opens.get(),
            fullyCachedAfter = cache.isCached(key, 0, expectedBytes),
            error = error,
        )
    }

    /**
     * An upstream that must never be consulted: every open is counted and fails, so a
     * cache miss cannot be papered over by the network.
     */
    class NeverUpstream : DataSource.Factory {
        val opens = AtomicInteger()

        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                opens.incrementAndGet()
                throw IOException("upstream consulted at position ${dataSpec.position}: not a cache hit")
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("upstream never opened")

            override fun getUri(): Uri? = null

            override fun close() = Unit
        }
    }
}
