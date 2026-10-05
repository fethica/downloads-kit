package com.fethica.downloadskit

/** Stands in for the media3 `DownloadManager` + `DownloadService` pair. */
internal class FakeDownloadCommands : DownloadCommands {
    val added = mutableListOf<Triple<String, String, DownloadMetadata>>()
    val removed = mutableListOf<String>()
    var removeAllCount = 0
    var pauseCount = 0
    var resumeCount = 0
    val requirements = mutableListOf<Int>()

    override fun add(id: String, uri: String, mimeType: String?, metadata: DownloadMetadata) {
        added += Triple(id, uri, metadata)
    }

    override fun remove(id: String) {
        removed += id
    }

    override fun removeAll() {
        removeAllCount++
    }

    override fun pause() {
        pauseCount++
    }

    override fun resume() {
        resumeCount++
    }

    override fun setRequirements(mask: Int) {
        requirements += mask
    }
}

/** Records what a host's analytics hook would have seen, in order. */
internal class RecordingListener : MediaDownloads.Listener {
    val events = mutableListOf<String>()
    override fun onStarted(id: String, group: String?) { events += "started:$id:$group" }
    override fun onCompleted(id: String, bytes: Long, seconds: Long) { events += "completed:$id:$bytes:$seconds" }
    override fun onFailed(id: String, reason: DownloadFailureReason) { events += "failed:$id:${reason.key}" }
    override fun onRemoved(id: String, bytes: Long) { events += "removed:$id:$bytes" }
    override fun onClearedAll(count: Int, bytes: Long) { events += "cleared:$count:$bytes" }
}

/**
 * Counts its reads as well as its writes: the real store is a preferences file, and a
 * read at the wrong moment is a disk read on whatever thread happened to be there.
 */
internal class FakeSettings(private var value: Boolean) : DownloadsSettings {
    var writes = 0
        private set

    var reads = 0
        private set

    override fun getWifiOnly(): Boolean {
        reads++
        return value
    }

    override fun setWifiOnly(enabled: Boolean) {
        value = enabled
        writes++
    }
}

internal fun snapshotOf(
    id: String,
    state: Int,
    bytes: Long = 0,
    contentLength: Long = -1,
    percent: Float = 0f,
    startTimeMs: Long = 0,
    updateTimeMs: Long = 0,
    group: String? = null,
    title: String = id,
) = DownloadSnapshot(
    id = id,
    state = state,
    percentDownloaded = percent,
    bytesDownloaded = bytes,
    contentLength = contentLength,
    startTimeMs = startTimeMs,
    updateTimeMs = updateTimeMs,
    failureReason = if (state == Media3.STATE_FAILED) Media3.FAILURE_REASON_UNKNOWN else Media3.FAILURE_REASON_NONE,
    stopReason = Media3.STOP_REASON_NONE,
    metadata = DownloadMetadata(title, null, group),
)
