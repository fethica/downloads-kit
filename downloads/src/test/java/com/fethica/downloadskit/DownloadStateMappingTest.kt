package com.fethica.downloadskit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Media3 `Download` states and progress, mapped onto the state a person sees. */
class DownloadStateMappingTest {

    private fun snapshot(
        state: Int,
        percent: Float = 0f,
        bytesDownloaded: Long = 0L,
        contentLength: Long = C_LENGTH_UNSET,
        startTimeMs: Long = 1_000L,
        updateTimeMs: Long = 1_000L,
        failureReason: Int = Media3.FAILURE_REASON_NONE,
        stopReason: Int = Media3.STOP_REASON_NONE,
    ) = DownloadSnapshot(
        id = "a",
        state = state,
        percentDownloaded = percent,
        bytesDownloaded = bytesDownloaded,
        contentLength = contentLength,
        startTimeMs = startTimeMs,
        updateTimeMs = updateTimeMs,
        failureReason = failureReason,
        stopReason = stopReason,
        metadata = DownloadMetadata("Title", null, null),
    )

    @Test
    fun `queued maps to Queued`() {
        assertEquals(DownloadState.Queued, snapshot(Media3.STATE_QUEUED).toDownloadState())
    }

    @Test
    fun `stopped maps to Queued because the library exposes no paused state`() {
        assertEquals(DownloadState.Queued, snapshot(Media3.STATE_STOPPED, stopReason = 1).toDownloadState())
    }

    @Test
    fun `restarting maps to Downloading`() {
        val state = snapshot(Media3.STATE_RESTARTING, percent = 10f, bytesDownloaded = 100).toDownloadState()
        assertEquals(DownloadState.Downloading(0.1f, 100), state)
    }

    @Test
    fun `downloading carries progress as a 0 to 1 fraction and the bytes so far`() {
        val state = snapshot(Media3.STATE_DOWNLOADING, percent = 42.5f, bytesDownloaded = 4_250).toDownloadState()
        assertEquals(DownloadState.Downloading(0.425f, 4_250), state)
    }

    @Test
    fun `downloading with an unknown percentage reports indeterminate progress`() {
        val state = snapshot(Media3.STATE_DOWNLOADING, percent = Float.NaN, bytesDownloaded = 12).toDownloadState()
        assertEquals(DownloadState.Downloading(DownloadState.PROGRESS_UNKNOWN, 12), state)
    }

    @Test
    fun `media3's percentage-unset sentinel is indeterminate progress`() {
        val state = snapshot(Media3.STATE_DOWNLOADING, percent = -1f, bytesDownloaded = 7).toDownloadState()
        assertEquals(DownloadState.Downloading(DownloadState.PROGRESS_UNKNOWN, 7), state)
    }

    @Test
    fun `downloading progress is clamped into range`() {
        val over = snapshot(Media3.STATE_DOWNLOADING, percent = 140f).toDownloadState()
        assertEquals(DownloadState.Downloading(1f, 0), over)
        val under = snapshot(Media3.STATE_DOWNLOADING, percent = -3f).toDownloadState()
        assertEquals(DownloadState.Downloading(0f, 0), under)
    }

    @Test
    fun `completed prefers the content length over the bytes counter`() {
        val state = snapshot(
            Media3.STATE_COMPLETED,
            bytesDownloaded = 900,
            contentLength = 1_000,
            updateTimeMs = 9_000,
        ).toDownloadState()
        assertEquals(DownloadState.Downloaded(1_000, 9_000), state)
    }

    @Test
    fun `completed falls back to bytes downloaded when the length is unset`() {
        val state = snapshot(Media3.STATE_COMPLETED, bytesDownloaded = 900, updateTimeMs = 9_000).toDownloadState()
        assertEquals(DownloadState.Downloaded(900, 9_000), state)
    }

    @Test
    fun `failed carries the reason`() {
        val state = snapshot(Media3.STATE_FAILED, failureReason = Media3.FAILURE_REASON_UNKNOWN)
            .toDownloadState(DownloadFailureReason.NETWORK)
        assertEquals(DownloadState.Failed(DownloadFailureReason.NETWORK), state)
    }

    @Test
    fun `failed without a classified cause is unknown`() {
        assertEquals(
            DownloadState.Failed(DownloadFailureReason.UNKNOWN),
            snapshot(Media3.STATE_FAILED, failureReason = Media3.FAILURE_REASON_UNKNOWN).toDownloadState(),
        )
    }

    @Test
    fun `removing maps to Removing`() {
        assertEquals(DownloadState.Removing, snapshot(Media3.STATE_REMOVING).toDownloadState())
    }

    @Test
    fun `an unknown media3 state degrades to NotDownloaded rather than throwing`() {
        assertEquals(DownloadState.NotDownloaded, snapshot(99).toDownloadState())
    }

    @Test
    fun `bytes for the entry row are the completed size when downloaded and the partial size otherwise`() {
        assertEquals(1_000L, snapshot(Media3.STATE_COMPLETED, contentLength = 1_000).toEntry().bytes)
        assertEquals(120L, snapshot(Media3.STATE_DOWNLOADING, bytesDownloaded = 120).toEntry().bytes)
        assertEquals(0L, snapshot(Media3.STATE_QUEUED).toEntry().bytes)
    }

    @Test
    fun `the entry carries the metadata the request was filed with`() {
        val entry = snapshot(Media3.STATE_COMPLETED, contentLength = 5)
            .copy(metadata = DownloadMetadata("Item 7", "Collection A", "group:42"))
            .toEntry()
        assertEquals("a", entry.id)
        assertEquals("Item 7", entry.title)
        assertEquals("Collection A", entry.subtitle)
        assertEquals("group:42", entry.group)
    }

    /**
     * What a restored index has to answer before the library starts the download
     * service: a DownloadManager comes up paused, so a queue that survived a reboot
     * only moves once something starts that service.
     */
    @Test
    fun `an unfinished download is what makes a restored index worth resuming`() {
        assertEquals(true, snapshot(Media3.STATE_QUEUED).isInFlight())
        assertEquals(true, snapshot(Media3.STATE_STOPPED).isInFlight())
        assertEquals(true, snapshot(Media3.STATE_DOWNLOADING).isInFlight())
        assertEquals(true, snapshot(Media3.STATE_RESTARTING).isInFlight())
    }

    @Test
    fun `a finished or departing download is not`() {
        assertEquals(false, snapshot(Media3.STATE_COMPLETED).isInFlight())
        assertEquals(false, snapshot(Media3.STATE_FAILED).isInFlight())
        assertEquals(false, snapshot(Media3.STATE_REMOVING).isInFlight())
    }

    private companion object {
        const val C_LENGTH_UNSET = -1L
    }
}
