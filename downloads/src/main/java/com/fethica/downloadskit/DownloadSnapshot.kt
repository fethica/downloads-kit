package com.fethica.downloadskit

/**
 * One media3 `Download`, flattened into plain values.
 *
 * This is the seam that keeps every mapping rule a pure function: the Android half of
 * the library converts a `Download` into this, and everything downstream — states,
 * entries, grouping, storage arithmetic, listener dispatch — is JVM-testable.
 */
internal data class DownloadSnapshot(
    val id: String,
    val state: Int,
    val percentDownloaded: Float,
    val bytesDownloaded: Long,
    val contentLength: Long,
    val startTimeMs: Long,
    val updateTimeMs: Long,
    val failureReason: Int,
    val stopReason: Int,
    val metadata: DownloadMetadata,
)

/** The size to show for this item: the real size once complete, the partial size while it runs. */
internal fun DownloadSnapshot.displayBytes(): Long = when (state) {
    Media3.STATE_COMPLETED -> if (contentLength > 0) contentLength else bytesDownloaded
    else -> bytesDownloaded.coerceAtLeast(0)
}

internal fun DownloadSnapshot.toDownloadState(
    failure: DownloadFailureReason = DownloadFailureReason.UNKNOWN,
): DownloadState = when (state) {
    Media3.STATE_QUEUED, Media3.STATE_STOPPED -> DownloadState.Queued
    Media3.STATE_DOWNLOADING, Media3.STATE_RESTARTING ->
        DownloadState.Downloading(progressFraction(), bytesDownloaded.coerceAtLeast(0))
    Media3.STATE_COMPLETED -> DownloadState.Downloaded(displayBytes(), updateTimeMs)
    Media3.STATE_FAILED -> DownloadState.Failed(failure)
    Media3.STATE_REMOVING -> DownloadState.Removing
    else -> DownloadState.NotDownloaded
}

internal fun DownloadSnapshot.toEntry(
    failure: DownloadFailureReason = DownloadFailureReason.UNKNOWN,
): DownloadEntry = DownloadEntry(
    id = id,
    title = metadata.title,
    subtitle = metadata.subtitle,
    group = metadata.group,
    state = toDownloadState(failure),
    bytes = displayBytes(),
)

/**
 * Whether media3 still has work to do on this download.
 *
 * The terminal states are completed, failed and removing; everything else is a
 * transfer that has not finished, which is what makes a restored index worth
 * starting the download service for.
 */
internal fun DownloadSnapshot.isInFlight(): Boolean = when (state) {
    Media3.STATE_QUEUED,
    Media3.STATE_STOPPED,
    Media3.STATE_DOWNLOADING,
    Media3.STATE_RESTARTING,
    -> true

    else -> false
}

/** Elapsed seconds between the download starting and its last index update. */
internal fun DownloadSnapshot.elapsedSeconds(): Long =
    ((updateTimeMs - startTimeMs).coerceAtLeast(0)) / 1_000L

private fun DownloadSnapshot.progressFraction(): Float {
    val percent = percentDownloaded
    // media3 reports C.PERCENTAGE_UNSET when the server never sent a content length.
    if (percent.isNaN() || percent == Media3.PERCENTAGE_UNSET) return DownloadState.PROGRESS_UNKNOWN
    return (percent / 100f).coerceIn(0f, 1f)
}
