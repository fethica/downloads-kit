package com.fethica.downloadskit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Grouped display, group ordering, and the storage arithmetic behind the summary row. */
class DownloadGroupingTest {

    private fun entry(
        id: String,
        group: String? = null,
        bytes: Long = 0,
        state: DownloadState = DownloadState.Downloaded(bytes, 0),
    ) = DownloadEntry(id = id, title = id, subtitle = null, group = group, state = state, bytes = bytes)

    @Test
    fun `entries without a group land in a single trailing group keyed null`() {
        val groups = groupDownloads(listOf(entry("a"), entry("b")))
        assertEquals(1, groups.size)
        assertEquals(null, groups[0].id)
        assertEquals(listOf("a", "b"), groups[0].entries.map { it.id })
    }

    @Test
    fun `entries are gathered by group id`() {
        val groups = groupDownloads(
            listOf(entry("a", "group:1"), entry("b", "group:2"), entry("c", "group:1")),
        )
        assertEquals(listOf("group:1", "group:2"), groups.map { it.id })
        assertEquals(listOf("a", "c"), groups[0].entries.map { it.id })
        assertEquals(listOf("b"), groups[1].entries.map { it.id })
    }

    @Test
    fun `groups keep the order they first appear in and ungrouped items sort last`() {
        val groups = groupDownloads(
            listOf(entry("loose"), entry("a", "group:9"), entry("b", "group:3"), entry("loose2")),
        )
        assertEquals(listOf("group:9", "group:3", null), groups.map { it.id })
        assertEquals(listOf("loose", "loose2"), groups.last().entries.map { it.id })
    }

    @Test
    fun `a group totals the bytes of its entries`() {
        val groups = groupDownloads(
            listOf(entry("a", "group:1", 1_000), entry("b", "group:1", 2_500)),
        )
        assertEquals(3_500L, groups[0].bytes)
    }

    @Test
    fun `an empty list produces no groups`() {
        assertEquals(emptyList<DownloadGroup>(), groupDownloads(emptyList()))
    }

    @Test
    fun `storage used sums every entry including partial downloads`() {
        val used = totalBytes(
            listOf(
                entry("a", bytes = 1_000),
                entry("b", bytes = 250, state = DownloadState.Downloading(0.25f, 250)),
                entry("c", bytes = 0, state = DownloadState.Queued),
            ),
        )
        assertEquals(1_250L, used)
    }

    @Test
    fun `storage used ignores entries on their way out`() {
        val used = totalBytes(
            listOf(entry("a", bytes = 1_000), entry("b", bytes = 400, state = DownloadState.Removing)),
        )
        assertEquals(1_000L, used)
    }

    @Test
    fun `storage used of nothing is zero`() {
        assertEquals(0L, totalBytes(emptyList()))
    }

    @Test
    fun `ids in a group are exposed in order for a group removal`() {
        val entries = listOf(entry("a", "group:1"), entry("b", "group:2"), entry("c", "group:1"))
        assertEquals(listOf("a", "c"), idsInGroup(entries, "group:1"))
        assertEquals(emptyList<String>(), idsInGroup(entries, "group:404"))
    }

    /**
     * What a surface asks before it offers "Go to downloads": not "is there a
     * download" but "is there one that plays with the network gone".
     */
    @Test
    fun `nothing finished is nothing to offer someone who has just gone offline`() {
        listOf(
            DownloadState.Queued,
            DownloadState.Downloading(0.4f, 400),
            DownloadState.Failed(DownloadFailureReason.NETWORK),
            DownloadState.Removing,
        ).forEach { state ->
            assertEquals(state.toString(), false, listOf(entry("a", state = state)).anyDownloaded())
        }
        assertEquals(false, emptyList<DownloadEntry>().anyDownloaded())
    }

    @Test
    fun `one finished download among unfinished ones is enough`() {
        val entries = listOf(
            entry("a", state = DownloadState.Queued),
            entry("b", bytes = 10, state = DownloadState.Downloaded(10, 0)),
        )
        assertEquals(true, entries.anyDownloaded())
    }

    // --- the two-level listing -----------------------------------------------

    @Test
    fun `a group is addressed by its id, and the loose bucket by the ungrouped key`() {
        val groups = groupDownloads(listOf(entry("a", "group:1"), entry("loose")))
        assertEquals("group:1", groups[0].key)
        assertEquals(UNGROUPED_KEY, groups[1].key)
    }

    @Test
    fun `a key finds the group it belongs to, and nothing for one that is gone`() {
        val groups = groupDownloads(listOf(entry("a", "group:1"), entry("loose")))
        assertEquals("group:1", groups.findByKey("group:1")?.id)
        assertEquals(null, groups.findByKey(UNGROUPED_KEY)?.id)
        assertEquals(listOf("loose"), groups.findByKey(UNGROUPED_KEY)?.entries?.map { it.id })
        assertEquals(null, groups.findByKey("group:404"))
        assertEquals(null, groups.findByKey(null))
    }

    /**
     * The strip at the top of the listing: a transfer is anything that has not
     * landed yet, because the whole reason it is pulled out of the groups is that
     * nothing about it is finished.
     */
    @Test
    fun `transfers are the queued, the running and the failed, in list order`() {
        val entries = listOf(
            entry("done", bytes = 10),
            entry("queued", state = DownloadState.Queued),
            entry("running", bytes = 5, state = DownloadState.Downloading(0.5f, 5)),
            entry("failed", state = DownloadState.Failed(DownloadFailureReason.NETWORK)),
            entry("going", bytes = 3, state = DownloadState.Removing),
        )
        assertEquals(listOf("queued", "running", "failed"), activeTransfers(entries).map { it.id })
    }

    @Test
    fun `nothing in flight is an empty strip, so the area disappears`() {
        assertEquals(emptyList<DownloadEntry>(), activeTransfers(listOf(entry("done", bytes = 10))))
        assertEquals(emptyList<DownloadEntry>(), activeTransfers(emptyList()))
    }

    /**
     * A group whose only download is still queued is STILL a group with downloads:
     * it has to be somewhere the person can reach, or removing it means waiting
     * for a transfer to finish first.
     */
    @Test
    fun `a group whose downloads are all in flight is still listed`() {
        val groups = groupDownloads(listOf(entry("a", "group:1", state = DownloadState.Queued)))
        assertEquals(listOf("group:1"), groups.map { it.id })
    }
    // --- blank group ids -----------------------------------------------------

    @Test
    fun `a blank group is the loose bucket, not a group of its own`() {
        val groups = groupDownloads(
            listOf(entry("empty", ""), entry("a", "group:1"), entry("loose"), entry("spaces", "  ")),
        )
        assertEquals(listOf("group:1", null), groups.map { it.id })
        assertEquals(listOf("empty", "loose", "spaces"), groups.last().entries.map { it.id })
    }

    @Test
    fun `no two groups share a key when blank and missing groups are mixed`() {
        val groups = groupDownloads(listOf(entry("empty", ""), entry("loose"), entry("a", "group:1")))
        assertEquals(groups.size, groups.map { it.key }.toSet().size)
    }

    @Test
    fun `the ungrouped key finds every loose entry, whether its group was missing or blank`() {
        val groups = groupDownloads(listOf(entry("empty", ""), entry("loose")))
        val loose = groups.findByKey(UNGROUPED_KEY)
        assertEquals(null, loose?.id)
        assertEquals(listOf("empty", "loose"), loose?.entries?.map { it.id })
    }

    @Test
    fun `a blank group names nothing to remove`() {
        val entries = listOf(entry("empty", ""), entry("loose"), entry("a", "group:1"))
        assertEquals(emptyList<String>(), idsInGroup(entries, ""))
        assertEquals(emptyList<String>(), idsInGroup(entries, " "))
        assertEquals(listOf("a"), idsInGroup(entries, "group:1"))
    }

    @Test
    fun `a blank group is filed as no group`() {
        assertEquals(null, normalizeGroup(""))
        assertEquals(null, normalizeGroup(" \t"))
        assertEquals(null, normalizeGroup(null))
        assertEquals("group:1", normalizeGroup("group:1"))
    }
}
