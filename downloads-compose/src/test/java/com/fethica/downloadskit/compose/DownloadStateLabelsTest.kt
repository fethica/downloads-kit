package com.fethica.downloadskit.compose

import com.fethica.downloadskit.DownloadFailureReason
import com.fethica.downloadskit.DownloadState
import com.fethica.downloadskit.compose.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which string each state reads out as, and what the progress ring is told to draw. */
class DownloadStateLabelsTest {

    @Test
    fun `every state has its own content description`() {
        val states = listOf(
            DownloadState.NotDownloaded,
            DownloadState.Queued,
            DownloadState.Downloading(0.5f, 1),
            DownloadState.Downloaded(1, 1),
            DownloadState.Failed(DownloadFailureReason.UNKNOWN),
            DownloadState.Removing,
        )
        val ids = states.map { contentDescriptionRes(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a download in progress reads out its percentage`() {
        assertEquals(R.string.downloads_download_state_downloading, contentDescriptionRes(DownloadState.Downloading(0.5f, 1)))
        assertEquals(50, contentDescriptionPercent(DownloadState.Downloading(0.5f, 1)))
    }

    @Test
    fun `a download with no known length reads out without a percentage`() {
        val state = DownloadState.Downloading(DownloadState.PROGRESS_UNKNOWN, 1)
        assertEquals(R.string.downloads_download_state_downloading_indeterminate, contentDescriptionRes(state))
        assertNull(contentDescriptionPercent(state))
    }

    @Test
    fun `percentages round to whole numbers`() {
        assertEquals(0, contentDescriptionPercent(DownloadState.Downloading(0.004f, 0)))
        assertEquals(100, contentDescriptionPercent(DownloadState.Downloading(1f, 0)))
        assertEquals(33, contentDescriptionPercent(DownloadState.Downloading(0.333f, 0)))
    }

    @Test
    fun `a failure reads out its reason and not a generic message`() {
        assertEquals(
            R.string.downloads_download_failure_network,
            failureMessageRes(DownloadFailureReason.NETWORK),
        )
        assertNotEquals(
            failureMessageRes(DownloadFailureReason.NETWORK),
            failureMessageRes(DownloadFailureReason.STORAGE_FULL),
        )
    }

    @Test
    fun `the ring is only determinate when the length is known`() {
        assertEquals(0.42f, ringProgress(DownloadState.Downloading(0.42f, 0)))
        assertNull(ringProgress(DownloadState.Downloading(DownloadState.PROGRESS_UNKNOWN, 0)))
        assertNull(ringProgress(DownloadState.Queued))
    }

    @Test
    fun `only a running download can be tapped to stop`() {
        assertEquals(true, isStoppable(DownloadState.Downloading(0.1f, 0)))
        assertEquals(true, isStoppable(DownloadState.Queued))
        assertEquals(false, isStoppable(DownloadState.Removing))
        assertEquals(false, isStoppable(DownloadState.NotDownloaded))
    }
}
