package com.fethica.downloadskit.compose

import com.fethica.downloadskit.DownloadGroup

/**
 * The lazy-list keys of [OfflineScreen]'s rows.
 *
 * Group and item ids are the host's own opaque strings, so a host can file a download
 * under a group called `"summary"` or give an item the id `"header"`. Each kind of row
 * therefore gets its own namespace: the screen's fixed rows under `chrome:`, groups under
 * `group:` (the loose bucket as `ungrouped`), transfers under `transfer:` and a group's
 * items under `item:`. No host id can produce a key from another namespace, and a lazy
 * list with two equal keys throws.
 *
 * These are row keys only. The value a host stores to say which group is open is still
 * [DownloadGroup.key].
 */
internal object OfflineRowKeys {
    const val SUMMARY: String = "chrome:summary"
    const val TRANSFERS_LABEL: String = "chrome:transfers"
    const val EMPTY: String = "chrome:empty"
    const val GROUP_HEADER: String = "chrome:header"
    const val UNGROUPED: String = "ungrouped"

    fun group(group: DownloadGroup): String = group.id?.let { "group:$it" } ?: UNGROUPED

    fun transfer(id: String): String = "transfer:$id"

    fun item(id: String): String = "item:$id"
}
