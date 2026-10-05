package com.fethica.downloadskit

/**
 * What a person sees for one downloadable item.
 *
 * Deliberately smaller than media3's seven `Download` states: a stopped download and a
 * queued one look the same from the outside, and a restarting one is still downloading.
 */
public sealed interface DownloadState {

    /** Nothing on disk, and nothing asked for. */
    public data object NotDownloaded : DownloadState

    /** Asked for, waiting its turn or waiting for Wi-Fi. */
    public data object Queued : DownloadState

    /**
     * @param progress 0f..1f, or [PROGRESS_UNKNOWN] when the server never sent a length.
     * @param bytes what has landed on disk so far.
     */
    public data class Downloading(val progress: Float, val bytes: Long) : DownloadState

    /**
     * @param bytes the size on disk.
     * @param completedAt wall-clock milliseconds of the last index update.
     */
    public data class Downloaded(val bytes: Long, val completedAt: Long) : DownloadState

    public data class Failed(val reason: DownloadFailureReason) : DownloadState

    /** On its way out; the row should stay visible but inert until it disappears. */
    public data object Removing : DownloadState

    public companion object {
        /** [Downloading.progress] when the content length is unknown: show a spinner, not a ring. */
        public const val PROGRESS_UNKNOWN: Float = -1f
    }
}
