package com.fethica.downloadskit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the facade does that does not need Android: the current set of downloads,
 * the flows over it, the Wi-Fi-only flag, and when each listener hook fires.
 *
 * The Android half feeds it snapshots and implements [DownloadCommands]; a test feeds it
 * the same snapshots and implements [DownloadCommands] with a fake.
 */
internal class DownloadsCore(
    private val commands: DownloadCommands,
    private val settings: DownloadsSettings,
    private val scope: CoroutineScope,
    /** Where the persisted flag is read: [DownloadsSettings] is a disk store. */
    private val settingsDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()

    /** Insertion-ordered so rows do not jump around as progress arrives. */
    private val known = LinkedHashMap<String, Row>()

    private val listeners = CopyOnWriteArrayList<MediaDownloads.Listener>()

    /**
     * A counter of everything the core hears, used instead of any clock.
     *
     * Each callback that changes or removes a row takes the next value and records it in
     * [lastHeard]; each read of the manager takes one with [markRead] before it starts.
     * A read then knows exactly which ids changed while it was out, including ids its
     * snapshot never listed, and leaves those to the core. `updateTimeMs` could not do
     * this: it only exists for ids IN the snapshot, and a wall clock can move backwards.
     */
    private var sequence = 0L

    /**
     * When each id last changed or was removed, by [sequence]. Never pruned: it holds one
     * number per id this process has touched, a few hundred at most, and pruning it would
     * let a slow read that started long ago mistake itself for news.
     */
    private val lastHeard = HashMap<String, Long>()

    /** The [markRead] of the newest index read applied so far; an older one is stale. */
    private var appliedIndexRead = -1L

    /**
     * Whether the removals now arriving belong to a [clearAll].
     *
     * The host is told about a clear ONCE, with a count and a total, and the manager
     * then reports every row it wipes one by one. Those are the same event twice, and
     * a listener that sends analytics was sending `offline_cleared` plus N
     * `download_removed` for a single tap.
     */
    private var clearing = false

    /** Whether an index read has landed, which is what makes [indexedIds] an answer. */
    private var indexLoaded = false

    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    private val _storageUsed = MutableStateFlow(0L)
    val storageUsed: StateFlow<Long> = _storageUsed.asStateFlow()

    /**
     * Wi-Fi-only, held at the RESTRICTIVE value until the persisted one has been read.
     *
     * Reading it is a disk read and the host builds this from `Application.onCreate`, so
     * it cannot happen here; [start] does it on [settingsDispatcher]. Starting from
     * Wi-Fi-only rather than from whatever the person chose is the whole point: a
     * transfer must never be let onto metered data because the setting had not loaded
     * yet, and the worst a too-strict default can do is hold a queue back for as long as
     * one preferences file takes to parse.
     */
    val wifiOnly: MutableStateFlow<Boolean> = MutableStateFlow(WIFI_ONLY_UNTIL_LOADED)

    /**
     * Reads the persisted Wi-Fi-only flag, then begins watching it, once the host is
     * ready to be called back.
     *
     * Deliberately NOT the constructor's job. A `StateFlow` replays its current value
     * the moment it is collected, so a collector launched from here would reach
     * [DownloadCommands] on another thread while the object that OWNS those commands is
     * still running its own constructor — and the Android implementation goes straight
     * at a `by lazy` download manager whose delegate has not been assigned yet.
     */
    fun start() {
        scope.launch {
            // The persisted flag is read here rather than in the constructor, and off
            // this scope: the host is inside `Application.onCreate` when start() is
            // called, and the first read opens a preferences file. compareAndSet rather
            // than an assignment, so somebody who reached the toggle in the meantime
            // keeps their choice.
            val persisted = withContext(settingsDispatcher) { settings.getWifiOnly() }
            wifiOnly.compareAndSet(WIFI_ONLY_UNTIL_LOADED, persisted)
            wifiOnly.collect { enabled ->
                if (settings.getWifiOnly() != enabled) settings.setWifiOnly(enabled)
                commands.setRequirements(requirementsMask(enabled))
            }
        }
    }

    fun addListener(listener: MediaDownloads.Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: MediaDownloads.Listener) {
        listeners.remove(listener)
    }

    fun all(): Flow<List<DownloadEntry>> = entries

    fun state(id: String): Flow<DownloadState> = entries
        .map { list -> list.firstOrNull { it.id == id }?.state ?: DownloadState.NotDownloaded }
        .distinctUntilChanged()

    fun request(id: String, uri: String, mimeType: String?, metadata: DownloadMetadata) {
        commands.add(id, uri, mimeType, metadata.withFiledGroup())
    }

    fun requestAll(items: List<DownloadCommand>) {
        items.forEach { commands.add(it.id, it.uri, it.mimeType, it.metadata.withFiledGroup()) }
    }

    fun remove(id: String) {
        commands.remove(id)
    }

    fun removeGroup(group: String) {
        idsInGroup(entries.value, group).forEach { commands.remove(it) }
    }

    fun clearAll() {
        val current = entries.value
        if (current.isNotEmpty()) {
            synchronized(lock) { clearing = true }
            dispatch { it.onClearedAll(current.size, totalBytes(current)) }
        }
        commands.removeAll()
    }

    fun pauseAll() {
        commands.pause()
    }

    fun resumeAll() {
        commands.resume()
    }

    /**
     * Call immediately BEFORE reading the manager (opening the index cursor, or taking
     * `currentDownloads` for a poll), and hand the result to [onIndexLoaded] or
     * [onProgress] with what was read.
     */
    fun markRead(): Long = synchronized(lock) { ++sequence }

    /**
     * The whole index, read back at startup. A restore is not a new download, so no
     * `onStarted` fires for anything it brings in.
     *
     * The read runs off the manager's looper, so a callback can land while the cursor
     * is open and the list that arrives here is then already out of date. Every id the
     * core heard about after [readMark] keeps what the core knows — the newer row, or
     * its absence after a removal — whether or not the snapshot lists it. Two reads can
     * also overlap (one at startup, one when the manager reports itself initialised), and
     * the older one landing second is dropped whole.
     *
     * @param readMark from [markRead], taken before the cursor was opened. Defaults to
     *   now, which is right for a snapshot that is current the moment it arrives.
     */
    fun onIndexLoaded(snapshots: List<DownloadSnapshot>, readMark: Long = markRead()) {
        synchronized(lock) {
            if (readMark < appliedIndexRead) return
            appliedIndexRead = readMark
            val previous = LinkedHashMap(known)
            known.clear()
            snapshots.forEach { snapshot ->
                val heardSince = (lastHeard[snapshot.id] ?: -1L) > readMark
                if (!heardSince) {
                    known[snapshot.id] = Row(snapshot, DownloadFailureReason.UNKNOWN)
                } else {
                    previous[snapshot.id]?.let { known[snapshot.id] = it }
                }
            }
            // Rows the cursor never saw because they arrived after it opened.
            previous.forEach { (id, row) ->
                if (id !in known && (lastHeard[id] ?: -1L) > readMark) known[id] = row
            }
            clearing = false
            indexLoaded = true
            publishLocked()
        }
    }

    /**
     * Every id the index carries, in any state, or null until the index has been read.
     *
     * Null is the point: before the read lands, an id missing from here is unknown, not
     * gone, and a host that prunes its own records by this answer must be able to tell
     * the two apart. Read under the same lock the rows change under, so the answer and
     * the loaded flag always describe the same moment.
     */
    fun indexedIds(): Set<String>? = synchronized(lock) {
        if (indexLoaded) known.keys.toSet() else null
    }

    /**
     * A poll tick, which is progress and nothing else.
     *
     * media3 reports state changes but never bytes, so the ring is driven by a poll of
     * the manager's current downloads. That list is read on the manager's looper and
     * applied on this core's dispatcher, so a completion or a removal can land in
     * between and the snapshot still in hand is a description of the past. Treating a
     * tick as a state change let a stale "downloading" undo a completion, bring a
     * removed row back and report a second start for it.
     *
     * A tick therefore only ever moves a row that is ALREADY in flight, and never
     * creates one.
     */
    fun onProgress(snapshots: List<DownloadSnapshot>, readMark: Long = markRead()) {
        synchronized(lock) {
            // An index read that started after this poll already carries newer rows.
            if (readMark < appliedIndexRead) return
            var changed = false
            for (snapshot in snapshots) {
                if (!snapshot.isInFlight()) continue
                val previous = known[snapshot.id] ?: continue
                if (!previous.snapshot.isInFlight()) continue
                // Parked, resumed or re-queued since the poll read it: that callback
                // is the newer word, even though both describe a download in flight.
                if ((lastHeard[snapshot.id] ?: -1L) > readMark) continue
                known[snapshot.id] = previous.copy(snapshot = snapshot)
                changed = true
            }
            if (changed) publishLocked()
        }
    }

    fun onDownloadChanged(snapshot: DownloadSnapshot, error: Throwable?) {
        val events = mutableListOf<(MediaDownloads.Listener) -> Unit>()
        synchronized(lock) {
            val previous = known[snapshot.id]
            val reason = if (snapshot.state == Media3.STATE_FAILED) classifyFailure(error) else DownloadFailureReason.UNKNOWN
            lastHeard[snapshot.id] = ++sequence
            // A row arriving out of nowhere ends any clear still being drained: the
            // removals it was swallowing are done with.
            if (previous == null) clearing = false
            // A RETRY is a new attempt and reports its own start. The failed row is
            // still in `known`, so "first sighting" alone missed every retry: the
            // attempt reported its failure or its completion with no start in front of
            // it, and the origin the host recorded on the retry tap was never consumed.
            val retried = previous?.snapshot?.state == Media3.STATE_FAILED && snapshot.isInFlight()
            if (previous == null || retried) {
                events += { it.onStarted(snapshot.id, normalizeGroup(snapshot.metadata.group)) }
            }
            if (snapshot.state == Media3.STATE_COMPLETED && previous?.snapshot?.state != Media3.STATE_COMPLETED) {
                events += { it.onCompleted(snapshot.id, snapshot.displayBytes(), snapshot.elapsedSeconds()) }
            }
            if (snapshot.state == Media3.STATE_FAILED && previous?.snapshot?.state != Media3.STATE_FAILED) {
                events += { it.onFailed(snapshot.id, reason) }
            }
            known[snapshot.id] = Row(snapshot, reason)
            publishLocked()
        }
        events.forEach { event -> dispatch(event) }
    }

    fun onDownloadRemoved(snapshot: DownloadSnapshot) {
        val bytes: Long
        val partOfAClear: Boolean
        synchronized(lock) {
            // The removal snapshot has already been zeroed out, so report the last size seen.
            bytes = known[snapshot.id]?.snapshot?.displayBytes() ?: snapshot.displayBytes()
            known.remove(snapshot.id)
            lastHeard[snapshot.id] = ++sequence
            partOfAClear = clearing
            if (clearing && known.isEmpty()) clearing = false
            publishLocked()
        }
        if (!partOfAClear) dispatch { it.onRemoved(snapshot.id, bytes) }
    }

    /**
     * Assigns the flows while still holding [lock], so publications land in the same
     * order as the changes they describe. Copying the rows under the lock and assigning
     * after letting go let a progress tick's older copy land after a completion's newer
     * one, leaving a finished download on screen as still running. Setting a StateFlow's
     * value is not a suspending call, and a collector resumed on this same thread can
     * only re-enter this monitor, which is reentrant, so holding the lock across it
     * cannot deadlock.
     */
    private fun publishLocked() {
        val entries = known.values.map { it.snapshot.toEntry(it.reason) }
        _entries.value = entries
        _storageUsed.value = totalBytes(entries)
    }

    private fun dispatch(event: (MediaDownloads.Listener) -> Unit) {
        listeners.forEach(event)
    }

    private data class Row(val snapshot: DownloadSnapshot, val reason: DownloadFailureReason)
}

/**
 * What [DownloadsCore.wifiOnly] holds before the persisted flag has been read: the
 * restrictive value, so the gap can never put a download on metered data.
 */
private const val WIFI_ONLY_UNTIL_LOADED = true
