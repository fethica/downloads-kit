package com.fethica.downloadskit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Listen offline: the whole downloads feature behind one object, created once in
 * `Application.onCreate` with [install].
 *
 * ```kotlin
 * class App : Application() {
 *     lateinit var downloads: MediaDownloads
 *     override fun onCreate() {
 *         super.onCreate()
 *         createDownloadNotificationChannel()
 *         downloads = MediaDownloads.install(
 *             this,
 *             DownloadsConfig(
 *                 cacheDirName = "downloads",
 *                 notificationSmallIcon = R.drawable.ic_download,
 *                 // The built-in store; or pass `settings` to keep the flag elsewhere.
 *                 preferencesName = "downloads",
 *                 userAgent = USER_AGENT,
 *             ),
 *         )
 *     }
 * }
 * ```
 *
 * The player side is one line: wrap the factory the player already uses with
 * [cacheDataSourceFactory] and a downloaded item plays from disk through the same URL.
 */
@OptIn(UnstableApi::class)
public class MediaDownloads private constructor(
    context: Context,
    internal val config: DownloadsConfig,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val core = DownloadsCore(
        ServiceCommands(),
        config.settings ?: DownloadsSettings.sharedPreferences(appContext, config.preferencesName!!),
        scope,
    )

    private val databaseProvider: DatabaseProvider by lazy { StandaloneDatabaseProvider(appContext) }

    private val httpDataSourceFactory: HttpDataSource.Factory by lazy {
        config.httpDataSourceFactory ?: DefaultHttpDataSource.Factory()
            .setUserAgent(config.userAgent)
            .setAllowCrossProtocolRedirects(true)
    }

    /**
     * Where downloaded bytes live: the app's private files dir, so no storage permission
     * is needed and uninstalling the app takes the downloads with it.
     *
     * Building it walks that directory and opens the index against SQLite, so every path
     * that reaches it does so off the main thread: [start] warms it on `Dispatchers.IO`,
     * and the player reaches it from its loading thread through [LazyDataSourceFactory].
     * The `by lazy` is SYNCHRONIZED, which is what keeps those two from ending up with a
     * second `SimpleCache` on one folder — media3 throws for that.
     */
    public val downloadCache: Cache by lazy {
        SimpleCache(
            File(appContext.filesDir, config.cacheDirName),
            // Downloads are removed on purpose, never evicted under the person's feet.
            NoOpCacheEvictor(),
            databaseProvider,
        )
    }

    internal val downloadManager: DownloadManager by lazy {
        DownloadManager(
            appContext,
            databaseProvider,
            downloadCache,
            httpDataSourceFactory,
            Executors.newFixedThreadPool(config.maxParallelDownloads.coerceAtLeast(1)),
        ).apply {
            maxParallelDownloads = config.maxParallelDownloads
            requirements = Requirements(requirementsMask(core.wifiOnly.value))
            addListener(managerListener)
        }
    }

    internal val scheduler: Scheduler? by lazy {
        config.schedulerJobId?.let { PlatformScheduler(appContext, it) }
    }

    internal val notificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(appContext, config.notificationChannelId)
    }

    private val managerListener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            reloadIndex()
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            core.onDownloadChanged(download.toSnapshot(), finalException)
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            core.onDownloadRemoved(download.toSnapshot())
        }
    }

    // ---------------------------------------------------------------- public API

    /**
     * Files one item. Safe to call again for an id already downloaded: media3 merges it.
     *
     * @param group the host's group id; null or blank files the item under no group.
     */
    public fun request(
        id: String,
        uri: Uri,
        title: String,
        subtitle: String? = null,
        artwork: ByteArray? = null,
        group: String? = null,
        mimeType: String? = null,
    ) {
        core.request(id, uri.toString(), mimeType, DownloadMetadata(title, subtitle, group, artwork))
    }

    /** Files a whole group of items in one go, in the order given. */
    public fun requestAll(items: List<DownloadItem>) {
        core.requestAll(
            items.map {
                DownloadCommand(
                    id = it.id,
                    uri = it.uri.toString(),
                    mimeType = it.mimeType,
                    metadata = DownloadMetadata(it.title, it.subtitle, it.group, it.artwork),
                )
            },
        )
    }

    public fun remove(id: String) {
        core.remove(id)
    }

    /** Removes every item filed under [group]. A blank [group] names no group and removes nothing. */
    public fun removeGroup(group: String) {
        core.removeGroup(group)
    }

    /** Removes everything, including partial downloads. */
    public fun clearAll() {
        core.clearAll()
    }

    public fun pauseAll() {
        core.pauseAll()
    }

    public fun resumeAll() {
        core.resumeAll()
    }

    /** The state of one item, [DownloadState.NotDownloaded] for anything never asked for. */
    public fun state(id: String): Flow<DownloadState> = core.state(id)

    /** Every download the index knows about, oldest first. */
    public fun all(): Flow<List<DownloadEntry>> = core.all()

    /**
     * Every id the index carries, in any state, or null until the index has been read
     * back at startup. A synchronous snapshot, for a host that keeps its own records per
     * download and must never treat "not loaded yet" as "not downloaded".
     */
    public fun indexedIds(): Set<String>? = core.indexedIds()

    /** Bytes on disk, partial downloads included. */
    public fun storageUsed(): Flow<Long> = core.storageUsed

    /**
     * Wi-Fi-only. Writing to it persists through [DownloadsConfig.settings] and re-arms
     * the media3 requirements, so a running download parks itself on a metered link.
     */
    public val wifiOnly: MutableStateFlow<Boolean> get() = core.wifiOnly

    public fun addListener(listener: Listener) {
        core.addListener(listener)
    }

    public fun removeListener(listener: Listener) {
        core.removeListener(listener)
    }

    /**
     * Wraps the factory the player already uses so a downloaded item plays from disk
     * through the same URL, and anything else streams exactly as before.
     *
     * The cache is read-only by default, which is what makes it safe to use for every
     * item in a mixed queue: a live stream flows straight through and writes nothing.
     *
     * Safe to call from `Service.onCreate`: nothing here touches the disk. The cache and
     * its index are opened on the player's loading thread, the first time an item
     * actually reads through them — see [LazyDataSourceFactory].
     *
     * @param writeThrough also cache what is streamed. Never turn this on for a player
     *   that also plays live streams.
     */
    public fun cacheDataSourceFactory(
        upstream: DataSource.Factory,
        writeThrough: Boolean = false,
    ): DataSource.Factory = LazyDataSourceFactory {
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(
                if (writeThrough) CacheDataSink.Factory().setCache(downloadCache) else null,
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Analytics hooks. Every method has a no-op default, so wire only what is wanted. */
    public interface Listener {
        public fun onStarted(id: String, group: String?) {}
        public fun onCompleted(id: String, bytes: Long, seconds: Long) {}
        public fun onFailed(id: String, reason: DownloadFailureReason) {}
        public fun onRemoved(id: String, bytes: Long) {}
        public fun onClearedAll(count: Int, bytes: Long) {}
    }

    /** Only for a host that tears its process down deliberately, such as in a test. */
    public fun release() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        runCatching { downloadManager.release() }
        runCatching { downloadCache.release() }
        synchronized(INSTALL_LOCK) { if (instance === this) instance = null }
    }

    // ---------------------------------------------------------------- internals

    private fun start() {
        // First, and only now: the core's flag collector calls back into
        // ServiceCommands, which reaches fields this object only finished building
        // when its constructor returned.
        core.start()
        scope.launch {
            // Building the cache and the index touches the disk; never on the main thread.
            withContext(Dispatchers.IO) { downloadManager }
            val mark = core.markRead()
            val restored = readIndex() ?: return@launch
            core.onIndexLoaded(restored, mark)
            if (restored.any { it.isInFlight() }) resumeRestoredDownloads()
        }
        scope.launch { pollProgress() }
    }

    private fun reloadIndex() {
        scope.launch {
            val mark = core.markRead()
            readIndex()?.let { core.onIndexLoaded(it, mark) }
        }
    }

    /**
     * The index as it stands, or null when it could not be read. A failed read is NOT an
     * empty index: applying it would wipe every row and tell the host its downloads are
     * gone, so it is skipped and the next read (or the callbacks) carries the truth.
     */
    private suspend fun readIndex(): List<DownloadSnapshot>? = withContext(Dispatchers.IO) {
        runCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.download.toSnapshot())
                }
            }
        }.onFailure { Log.w(TAG, "the download index could not be read", it) }.getOrNull()
    }

    /**
     * Starts the download service for a queue the index brought back.
     *
     * A [DownloadManager] is built PAUSED — media3 1.9.2 sets `downloadsPaused` in its
     * constructor — and the only thing that clears it is `DownloadService.onCreate`.
     * Nothing else here starts that service, so downloads restored after a reboot or a
     * process death sat at Queued until the next add or removal happened to start it for
     * unrelated reasons.
     *
     * [DownloadsConfig.schedulerJobId] does not cover this on a current phone: media3
     * asks for `getScheduler()` only below API 31, so from Android 12 on no job is
     * scheduled, the RESTART filter is never used, and the first thing that can resume a
     * restored queue is the app being opened. That is what this is.
     *
     * A plain background start is tried first and a foreground one only if the platform
     * refuses it. From a state where neither is allowed there is nothing left to try, and
     * a crash would be a poor trade for a transfer the next launch will pick up.
     */
    private fun resumeRestoredDownloads() {
        dispatchToService { foreground ->
            DownloadService.sendResumeDownloads(
                appContext,
                config.serviceClass,
                foreground,
            )
        }
    }

    /**
     * media3 only reports state changes, not progress ticks, so the ring is driven by a
     * poll that runs only while something is actually in flight.
     *
     * The list is read on the manager's looper and applied on this scope's dispatcher,
     * so a completion or a removal can land in between. What is in hand by then is a
     * description of the past, which is why it goes in as PROGRESS rather than as a
     * state change — see [DownloadsCore.onProgress].
     */
    private suspend fun pollProgress() {
        core.entries
            .map { list -> list.any { it.state is DownloadState.Downloading || it.state is DownloadState.Queued } }
            .distinctUntilChanged()
            .collect { active ->
                if (!active) return@collect
                val manager = withContext(Dispatchers.IO) { downloadManager }
                while (currentCoroutineIsActive()) {
                    delay(PROGRESS_POLL_MS)
                    // Marked on the looper, in the same step as the read, so a callback
                    // the looper delivers after it counts as newer than this tick.
                    val (mark, current) = withContext(Dispatchers.Main) {
                        core.markRead() to manager.currentDownloads
                    }
                    if (current.isEmpty()) break
                    core.onProgress(current.map { it.toSnapshot() }, mark)
                }
            }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive ?: true

    /**
     * Adds and removals go through the foreground service, so the transfer survives the
     * app going to the background. Everything else talks to the manager we own.
     */
    private inner class ServiceCommands : DownloadCommands {
        override fun add(id: String, uri: String, mimeType: String?, metadata: DownloadMetadata) {
            val request = DownloadRequest.Builder(id, Uri.parse(uri))
                .setMimeType(mimeType)
                // No customCacheKey on purpose: the cache key stays the URL, which is what
                // lets the player read the downloaded bytes without knowing the id.
                .setData(metadata.encode())
                .build()
            dispatchToService { foreground ->
                DownloadService.sendAddDownload(appContext, config.serviceClass, request, foreground)
            }
        }

        override fun remove(id: String) {
            dispatchToService { foreground ->
                DownloadService.sendRemoveDownload(appContext, config.serviceClass, id, foreground)
            }
        }

        override fun removeAll() {
            dispatchToService { foreground ->
                DownloadService.sendRemoveAllDownloads(appContext, config.serviceClass, foreground)
            }
        }

        override fun pause() = onManager { it.pauseDownloads() }

        override fun resume() = onManager { it.resumeDownloads() }

        override fun setRequirements(mask: Int) = onManager { it.requirements = Requirements(mask) }
    }

    /**
     * `startService` is refused from the background on API 26+. Retrying as a foreground
     * start covers the case where the request came from a notification action or a
     * background worker rather than from a visible screen. If the platform refuses that
     * too there is nothing left to try, and a crash would be a poor trade for a download.
     */
    private inline fun dispatchToService(send: (foreground: Boolean) -> Unit) {
        try {
            send(false)
        } catch (_: IllegalStateException) {
            runCatching { send(true) }
                .onFailure { Log.w(TAG, "the platform refused to start the download service", it) }
        }
    }

    /**
     * Building the manager touches SQLite and the cache index, so it is always reached
     * off the main thread; the manager itself is then only ever driven from the looper
     * it was built against, in the order the calls were made — see [OrderedManagerCalls].
     *
     * `by lazy` so the queue's consumer, which reaches [downloadManager], is launched by
     * the first call rather than while this object is still running its constructor.
     */
    private val managerCalls: OrderedManagerCalls<DownloadManager> by lazy {
        OrderedManagerCalls(
            scope = scope,
            resolve = { withContext(Dispatchers.IO) { downloadManager } },
            target = Dispatchers.Main,
            onFailure = { Log.w(TAG, "a download manager command failed", it) },
        )
    }

    private fun onManager(block: (DownloadManager) -> Unit) {
        managerCalls.submit(block)
    }

    public companion object {
        private val INSTALL_LOCK = Any()

        @Volatile
        private var instance: MediaDownloads? = null

        /**
         * Creates the one instance for this process. Call it from `Application.onCreate`,
         * after the notification channel exists.
         */
        public fun install(context: Context, config: DownloadsConfig): MediaDownloads =
            synchronized(INSTALL_LOCK) {
                instance ?: MediaDownloads(context, config).also {
                    instance = it
                    it.start()
                }
            }

        /** The installed instance, or null before `Application.onCreate` has run. */
        public fun get(): MediaDownloads? = instance

        /** The installed instance. Throws if the host forgot to call [install]. */
        public fun required(): MediaDownloads = checkNotNull(instance) {
            "MediaDownloads.install(context, config) must be called from Application.onCreate()"
        }
    }
}

/** One item for [MediaDownloads.requestAll]. A null or blank [group] files it under no group. */
public class DownloadItem(
    public val id: String,
    public val uri: Uri,
    public val title: String,
    public val subtitle: String? = null,
    public val artwork: ByteArray? = null,
    public val group: String? = null,
    public val mimeType: String? = null,
)

@OptIn(UnstableApi::class)
internal fun Download.toSnapshot(): DownloadSnapshot = DownloadSnapshot(
    id = request.id,
    state = state,
    percentDownloaded = percentDownloaded,
    bytesDownloaded = bytesDownloaded,
    contentLength = contentLength,
    startTimeMs = startTimeMs,
    updateTimeMs = updateTimeMs,
    failureReason = failureReason,
    stopReason = stopReason,
    metadata = DownloadMetadata.decode(request.data),
)

private const val PROGRESS_POLL_MS = 1_000L
private const val TAG = "MediaDownloads"
