package com.fethica.downloadskit

/** One item a host asked for, in the form the engine takes it. */
internal data class DownloadCommand(
    val id: String,
    val uri: String,
    val mimeType: String?,
    val metadata: DownloadMetadata,
)

/**
 * The write half of the engine: the media3 `DownloadService` and `DownloadManager`
 * behind an interface, so the facade's rules can be driven by a fake.
 */
internal interface DownloadCommands {
    fun add(id: String, uri: String, mimeType: String?, metadata: DownloadMetadata)
    fun remove(id: String)
    fun removeAll()
    fun pause()
    fun resume()
    fun setRequirements(mask: Int)
}
