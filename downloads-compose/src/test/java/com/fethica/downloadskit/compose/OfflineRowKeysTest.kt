package com.fethica.downloadskit.compose

import com.fethica.downloadskit.DownloadEntry
import com.fethica.downloadskit.DownloadState
import com.fethica.downloadskit.activeTransfers
import com.fethica.downloadskit.groupDownloads
import org.junit.Assert.assertEquals
import org.junit.Test

/** Host ids that look like the screen's own rows must never produce a duplicate key. */
class OfflineRowKeysTest {

    private fun entry(id: String, group: String?, state: DownloadState = DownloadState.Downloaded(1, 0)) =
        DownloadEntry(id = id, title = id, subtitle = null, group = group, state = state, bytes = 1)

    /** The keys of level one, in the order the screen emits them. */
    private fun levelOneKeys(entries: List<DownloadEntry>): List<String> {
        val groups = groupDownloads(entries)
        val transfers = activeTransfers(entries)
        return buildList {
            add(OfflineRowKeys.SUMMARY)
            if (transfers.isNotEmpty()) {
                add(OfflineRowKeys.TRANSFERS_LABEL)
                transfers.forEach { add(OfflineRowKeys.transfer(it.id)) }
            }
            if (groups.isEmpty()) add(OfflineRowKeys.EMPTY)
            groups.forEach { add(OfflineRowKeys.group(it)) }
        }
    }

    private fun assertUnique(keys: List<String>) {
        assertEquals(keys.toString(), keys.size, keys.toSet().size)
    }

    @Test
    fun `groups named like the screen's own rows do not collide with them`() {
        val hostile = listOf(
            "summary", "transfers-header", "empty", "header", "ungrouped",
            "chrome:summary", "chrome:transfers", "chrome:empty", "transfer:foo", "item:foo",
        )
        val entries = hostile.mapIndexed { i, group -> entry("id$i", group) } + entry("loose", null)
        assertUnique(levelOneKeys(entries))
    }

    @Test
    fun `a group named after a transfer row does not collide with that transfer`() {
        val entries = listOf(
            entry("foo", "album", DownloadState.Queued),
            entry("bar", "transfer-foo"),
            entry("baz", "transfer:foo"),
        )
        val keys = levelOneKeys(entries)
        assertUnique(keys)
        assertEquals(true, OfflineRowKeys.transfer("foo") in keys)
    }

    @Test
    fun `items named like the group header do not collide with it`() {
        val ids = listOf("header", "summary", "chrome:header", "group:header", "transfer:header", "")
        val keys = listOf(OfflineRowKeys.GROUP_HEADER) + ids.map { OfflineRowKeys.item(it) }
        assertUnique(keys)
    }

    @Test
    fun `the loose bucket and a group with any id never share a key`() {
        val keys = groupDownloads(
            listOf(entry("a", "ungrouped"), entry("b", null), entry("c", "group:"), entry("d", "")),
        ).map { OfflineRowKeys.group(it) }
        assertUnique(keys)
    }

    @Test
    fun `chrome keys live in their own namespace`() {
        listOf(
            OfflineRowKeys.SUMMARY,
            OfflineRowKeys.TRANSFERS_LABEL,
            OfflineRowKeys.EMPTY,
            OfflineRowKeys.GROUP_HEADER,
        ).forEach { assertEquals(it, true, it.startsWith("chrome:")) }
        assertEquals("transfer:summary", OfflineRowKeys.transfer("summary"))
        assertEquals("item:header", OfflineRowKeys.item("header"))
    }
}
