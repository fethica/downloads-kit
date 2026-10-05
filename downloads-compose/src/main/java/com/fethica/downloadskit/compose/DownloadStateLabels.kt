package com.fethica.downloadskit.compose

import androidx.annotation.StringRes
import com.fethica.downloadskit.DownloadFailureReason
import com.fethica.downloadskit.DownloadState
import com.fethica.downloadskit.compose.R
import kotlin.math.roundToInt

/** The string a state reads out as, for the button's `contentDescription`. */
@StringRes
internal fun contentDescriptionRes(state: DownloadState): Int = when (state) {
    DownloadState.NotDownloaded -> R.string.downloads_download_action_download
    DownloadState.Queued -> R.string.downloads_download_state_queued
    is DownloadState.Downloading ->
        if (state.progress == DownloadState.PROGRESS_UNKNOWN) {
            R.string.downloads_download_state_downloading_indeterminate
        } else {
            R.string.downloads_download_state_downloading
        }
    is DownloadState.Downloaded -> R.string.downloads_download_state_downloaded
    is DownloadState.Failed -> R.string.downloads_download_state_failed
    DownloadState.Removing -> R.string.downloads_download_state_removing
}

/** The whole percentage that goes into the description, or null when there is none to say. */
internal fun contentDescriptionPercent(state: DownloadState): Int? {
    val progress = (state as? DownloadState.Downloading)?.progress ?: return null
    if (progress == DownloadState.PROGRESS_UNKNOWN) return null
    return (progress * 100f).roundToInt()
}

@StringRes
internal fun failureMessageRes(reason: DownloadFailureReason): Int = when (reason) {
    DownloadFailureReason.UNKNOWN -> R.string.downloads_download_failure_unknown
    DownloadFailureReason.NETWORK -> R.string.downloads_download_failure_network
    DownloadFailureReason.HTTP -> R.string.downloads_download_failure_http
    DownloadFailureReason.STORAGE_FULL -> R.string.downloads_download_failure_storage_full
}

/** The sweep for the progress ring, or null to spin instead. */
internal fun ringProgress(state: DownloadState): Float? {
    val progress = (state as? DownloadState.Downloading)?.progress ?: return null
    return progress.takeIf { it != DownloadState.PROGRESS_UNKNOWN }
}

/** Whether tapping cancels what is in flight. */
internal fun isStoppable(state: DownloadState): Boolean =
    state is DownloadState.Downloading || state == DownloadState.Queued
