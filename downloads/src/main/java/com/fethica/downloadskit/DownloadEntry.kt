package com.fethica.downloadskit

/**
 * One row of the offline library. Everything here comes out of the media3 download
 * index, so the offline screen draws with no network and no catalogue lookup.
 *
 * There is deliberately no `uri` field: the host already owns the item's URL, and
 * keeping the row free of `android.net.Uri` keeps the grouping and storage arithmetic
 * testable on the JVM.
 */
public data class DownloadEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val group: String?,
    val state: DownloadState,
    val bytes: Long,
)

/**
 * Whether any of these rows would actually play with no connection.
 *
 * A queued, half-transferred or failed download still needs the network it exists to
 * replace, and a removing one is on its way out, so none of them is somewhere to send
 * a person who has just gone offline.
 */
public fun List<DownloadEntry>.anyDownloaded(): Boolean =
    any { it.state is DownloadState.Downloaded }
