package com.fethica.downloadskit

/**
 * The entries a host filed under one `group` (an album, a series, a collection id), with
 * their total size, as the offline screen draws them.
 *
 * @param id the group key, or null for the bucket of entries filed without one.
 */
public data class DownloadGroup(
    val id: String?,
    val entries: List<DownloadEntry>,
    val bytes: Long,
)

/**
 * Gathers entries by [DownloadEntry.group], preserving the order the groups first
 * appear in and the order of entries inside each one. Ungrouped entries collect into a
 * single trailing group keyed null.
 */
public fun groupDownloads(entries: List<DownloadEntry>): List<DownloadGroup> {
    if (entries.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<DownloadEntry>>()
    val loose = mutableListOf<DownloadEntry>()
    for (entry in entries) {
        val key = entry.group
        if (key == null) loose += entry else grouped.getOrPut(key) { mutableListOf() } += entry
    }
    val groups = grouped.map { (key, items) -> DownloadGroup(key, items, totalBytes(items)) }
    return if (loose.isEmpty()) groups else groups + DownloadGroup(null, loose, totalBytes(loose))
}

/**
 * The bytes these entries occupy. Entries being removed are excluded: their space is
 * already spoken for, and counting it makes the summary row jump.
 */
public fun totalBytes(entries: List<DownloadEntry>): Long =
    entries.sumOf { if (it.state is DownloadState.Removing) 0L else it.bytes }

/** The ids filed under [group], in order — what a "remove this whole group" action needs. */
public fun idsInGroup(entries: List<DownloadEntry>, group: String): List<String> =
    entries.filter { it.group == group }.map { it.id }

/**
 * The key the loose bucket is addressed by.
 *
 * A two-level listing has to be able to name the level it is on with one value, and
 * `null` is already taken: it is what "show the list of groups" means. The empty string
 * cannot collide with a real group, because a blank group is filed as no group at all.
 */
public const val UNGROUPED_KEY: String = ""

/** How a listing addresses this group: its id, or [UNGROUPED_KEY]. */
public val DownloadGroup.key: String get() = id ?: UNGROUPED_KEY

/**
 * The group [key] names, or null when nothing is filed under it any more.
 *
 * Null rather than a throw for the case that actually happens: the last item of the
 * open group is removed, and the screen has to fall back to the list of groups.
 */
public fun List<DownloadGroup>.findByKey(key: String?): DownloadGroup? =
    if (key == null) null else firstOrNull { it.key == key }

/**
 * The transfers worth showing apart from the groups: everything that has not landed.
 *
 * Queued, running and failed rows are the ones a person is waiting on, and a listing
 * that files them away under their group hides the only thing they need — which is why
 * they get their own strip at the top. A row on its way out is not a transfer.
 */
public fun activeTransfers(entries: List<DownloadEntry>): List<DownloadEntry> =
    entries.filter {
        it.state == DownloadState.Queued ||
            it.state is DownloadState.Downloading ||
            it.state is DownloadState.Failed
    }
