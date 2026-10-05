package com.fethica.downloadskit

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * A store written by the previous version of this library, before its package moved,
 * must open as it is: completed items stay complete and playable from disk, a queued
 * item keeps its partial bytes, the Wi-Fi-only choice survives, and nothing is fetched
 * again or lost.
 *
 * The store under `fixtures/legacy-store-v1.zip` holds the Media3 database, the cache
 * directory with its `.uid` file and both preference files exactly as the previous
 * version left them; `legacy-store-v1.json` lists what went in, with payload hashes.
 * The network is switched off and the HTTP stack throws, so any attempt to download
 * again fails the test instead of passing quietly.
 */
@RunWith(RobolectricTestRunner::class)
// SDK 35: Robolectric 4.17 cannot set up an SDK 36 sandbox on JDK 21 without extra
// --add-exports flags. The storage formats under test do not depend on the API level.
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class LegacyStoreCompatibilityTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File get() = app.filesDir.parentFile!!
    private val manifest: JSONObject by lazy { JSONObject(resource("legacy-store-v1.json").decodeToString()) }
    private val httpOpens = AtomicInteger()
    private var downloads: MediaDownloads? = null
    private val serviceStarts = mutableListOf<Intent>()

    @Before
    fun offline() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)
    }

    @After
    fun tearDown() {
        downloads?.release()
    }

    @Test
    fun `a store from the previous version opens with every item, byte and setting intact`() {
        unpack()
        val catalogBefore = File(dataDir, "shared_prefs/offline_prefs.xml").readBytes()
        val settingsBefore = File(dataDir, "shared_prefs/offline_downloads.xml").readBytes()
        val items = manifest.getJSONArray("items").let { array -> List(array.length()) { array.getJSONObject(it) } }

        val media = install()
        // Nothing has been read back yet: unknown, not empty.
        assertNull(media.indexedIds())

        assertEquals(items.map { it.getString("id") }.toSet(), await { media.indexedIds() })
        val entries = await { runBlocking { media.all().first() }.takeIf { it.size == items.size } }

        items.forEach { item ->
            val id = item.getString("id")
            val entry = entries.single { it.id == id }
            assertEquals(item.getString("title"), entry.title)
            assertEquals(item.optStringOrNull("subtitle"), entry.subtitle)
            assertEquals(item.optStringOrNull("group"), entry.group)
            assertEquals(item.getLong("bytesDownloaded"), entry.bytes)
            when (item.getString("state")) {
                "completed" -> {
                    val state = entry.state as DownloadState.Downloaded
                    assertEquals(item.getLong("contentLength"), state.bytes)
                }
                else -> {
                    assertEquals(DownloadState.Queued, entry.state)
                    assertFalse(listOf(entry).anyDownloaded())
                }
            }
        }

        val cache = media.downloadCache
        items.forEach { item ->
            val key = item.getString("uri")
            val length = item.getLong("contentLength")
            val cached = item.getLong("bytesDownloaded")
            assertEquals(length, ContentMetadata.getContentLength(cache.getContentMetadata(key)))
            assertEquals(cached, cache.getCachedBytes(key, 0, length))
            assertEquals(cached == length, cache.isCached(key, 0, length))
            // Every byte read from disk through the player's factory, with an upstream
            // that refuses to be opened.
            if (cached == length) assertEquals(item.getString("sha256"), sha256(readThroughPlayerFactory(media, key)))
        }

        // The queued item is still in flight, so the restore starts the download service.
        val resume = await { startedServices().firstOrNull { it.action == ACTION_RESUME } }
        assertEquals(MediaDownloadService::class.java.name, resume.component!!.className)

        await { if (!media.wifiOnly.value) Unit else null }
        assertEquals(manifest.getJSONObject("preferences").getBoolean("wifi_only"), media.wifiOnly.value)

        assertEquals(0, httpOpens.get())
        assertArrayEquals(catalogBefore, File(dataDir, "shared_prefs/offline_prefs.xml").readBytes())
        assertArrayEquals(settingsBefore, File(dataDir, "shared_prefs/offline_downloads.xml").readBytes())
    }

    @Test
    fun `removing one restored item leaves the others and their bytes alone`() {
        unpack()
        val items = manifest.getJSONArray("items").let { array -> List(array.length()) { array.getJSONObject(it) } }
        val loose = items.single { it.isNull("group") }
        val kept = items - loose

        val media = install()
        await { media.indexedIds()?.takeIf { it.size == items.size } }

        media.remove(loose.getString("id"))
        val intent = await { startedServices().singleOrNull { it.action == ACTION_REMOVE } }
        assertEquals(loose.getString("id"), intent.getStringExtra("content_id"))
        val service = Robolectric.buildService(MediaDownloadService::class.java, intent).create().startCommand(0, 1)
        try {
            assertEquals(kept.map { it.getString("id") }.toSet(), await { media.indexedIds()?.takeIf { it.size == kept.size } })
            val cache = media.downloadCache
            await { if (cache.getCachedBytes(loose.getString("uri"), 0, loose.getLong("contentLength")) == 0L) Unit else null }
            kept.forEach { item ->
                assertEquals(
                    item.getLong("bytesDownloaded"),
                    cache.getCachedBytes(item.getString("uri"), 0, item.getLong("contentLength")),
                )
            }
            val entries = runBlocking { media.all().first() }
            assertTrue(entries.single { it.state is DownloadState.Downloaded }.title.isNotEmpty())
            assertEquals(1, entries.count { it.state == DownloadState.Queued })
        } finally {
            service.destroy()
        }
        assertEquals(0, httpOpens.get())
    }

    @Test
    fun `metadata from a newer codec reads as the empty record and the row still loads`() {
        val newer = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeByte(2)
                out.writeUTF("Item from a newer version")
                out.writeLong(42L)
            }
        }.toByteArray()
        assertEquals(DownloadMetadata.EMPTY, DownloadMetadata.decode(newer))

        val provider = StandaloneDatabaseProvider(app)
        val request = DownloadRequest.Builder("item-newer", Uri.parse("https://example.invalid/media/newer.wav"))
            .setMimeType("audio/wav")
            .setData(newer)
            .build()
        val progress = DownloadProgress().apply {
            bytesDownloaded = 1024
            percentDownloaded = 100f
        }
        DefaultDownloadIndex(provider).putDownload(
            Download(request, Download.STATE_COMPLETED, 1L, 2L, 1024L, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE, progress),
        )
        provider.close()

        val media = install()
        assertEquals(setOf("item-newer"), await { media.indexedIds()?.takeIf { it.isNotEmpty() } })
        val entry = await { runBlocking { media.all().first() }.singleOrNull() }
        assertEquals("", entry.title)
        assertNull(entry.subtitle)
        assertNull(entry.group)
        assertTrue(entry.state is DownloadState.Downloaded)
    }

    // ---------------------------------------------------------------- helpers

    private fun install(): MediaDownloads = MediaDownloads.install(
        app,
        DownloadsConfig(
            cacheDirName = "offline_downloads",
            notificationSmallIcon = android.R.drawable.stat_sys_download,
            preferencesName = "offline_downloads",
            notificationChannelId = "downloads",
            httpDataSourceFactory = RefusingHttpFactory(httpOpens),
        ),
    ).also { downloads = it }

    private fun unpack() {
        ZipInputStream(resource("legacy-store-v1.zip").inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(dataDir, entry.name)
                check(target.canonicalPath.startsWith(dataDir.canonicalPath + File.separator))
                target.parentFile!!.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        val files = manifest.getJSONArray("files")
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            assertEquals(file.getString("sha256"), sha256(File(dataDir, file.getString("path")).readBytes()))
        }
        assertEquals(File(dataDir, "databases/exoplayer_internal.db"), app.getDatabasePath("exoplayer_internal.db"))
    }

    private fun readThroughPlayerFactory(media: MediaDownloads, key: String): ByteArray {
        val refusing = DataSource.Factory { RefusingHttpFactory(httpOpens).createDataSource() }
        val source = media.cacheDataSourceFactory(refusing).createDataSource()
        val out = ByteArrayOutputStream()
        try {
            source.open(DataSpec(Uri.parse(key)))
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == -1) break
                out.write(buffer, 0, read)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    /** Every service start the library asked for so far, in order. */
    private fun startedServices(): List<Intent> {
        val shadow = shadowOf(app)
        generateSequence { shadow.nextStartedService }.forEach { serviceStarts += it }
        return serviceStarts
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    private fun <T : Any> await(timeoutMs: Long = 15_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            probe()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "timed out waiting for the store to load" }
            Thread.sleep(20)
        }
    }

    /** An HTTP stack that counts every attempt to open a connection and refuses it. */
    private class RefusingHttpFactory(private val opens: AtomicInteger) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource {
            val real = DefaultHttpDataSource.Factory().createDataSource()
            return object : HttpDataSource by real {
                override fun open(dataSpec: DataSpec): Long {
                    opens.incrementAndGet()
                    throw IOException("no network in this test: ${dataSpec.uri}")
                }
            }
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory = this
    }

    private companion object {
        const val ACTION_RESUME = "androidx.media3.exoplayer.downloadService.action.RESUME_DOWNLOADS"
        const val ACTION_REMOVE = "androidx.media3.exoplayer.downloadService.action.REMOVE_DOWNLOAD"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
    }
}
