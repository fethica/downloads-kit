package com.fethica.downloadskit.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fethica.downloadskit.DownloadEntry
import com.fethica.downloadskit.DownloadGroup
import com.fethica.downloadskit.DownloadState
import com.fethica.downloadskit.compose.R
import com.fethica.downloadskit.activeTransfers
import com.fethica.downloadskit.findByKey
import com.fethica.downloadskit.groupDownloads
import com.fethica.downloadskit.key

/**
 * The offline library on two levels: the GROUPS that have downloads, and one group's
 * items.
 *
 * Putting both on one screen — a storage summary, then a header per group, then that
 * group's rows, with a "remove this whole group" bin on the header — reads as neither a
 * list nor a set of sections, and puts the one control that can delete a hundred files
 * in the part of a list a thumb passes over on its way down. That is why the screen is
 * split in two.
 *
 * So: nothing that LOOKS like a header does anything. The only tappable things at the
 * first level are a group row (opens it), the Wi-Fi switch, "Clear all", and the controls
 * on a transfer that has not landed. Everything destructive that reaches more than one
 * item asks first.
 *
 * Which level is showing is the caller's state, not this screen's, so the host's title
 * bar can name the open group and its back button can close it — see [openGroup].
 *
 * There is no swipe-to-delete: a list a person scrolls with one thumb in a car dock
 * should not lose an item to a stray horizontal drag.
 *
 * Everything is drawn from the host's `MaterialTheme`; the screen has no colours,
 * shapes or type of its own.
 *
 * @param openGroup the group being shown, as [DownloadGroup.key], or null for the list of
 *   groups. A key nothing is filed under any more falls back to the list, which is what
 *   happens when the last item of the open group is removed.
 * @param onOpenGroup asked to change [openGroup]. Called with null to go back up.
 * @param onRetry a failed transfer, asked for again. The library has no URL to retry
 *   with — a row deliberately carries no `uri` — so only the host can do this.
 * @param groupTitle turns a group id into something worth reading. The default shows
 *   the id, which is only ever right in a sample.
 * @param artwork how the host draws a group's picture; see [DownloadArtwork].
 * @param groupLink the host's "go to this group" action on level two; see
 *   [GroupLinkFor]. The default offers none, which is what a library with no
 *   host around it can honestly do.
 */
@Composable
public fun OfflineScreen(
    downloads: List<DownloadEntry>,
    storageUsed: Long,
    wifiOnly: Boolean,
    openGroup: String?,
    onOpenGroup: (String?) -> Unit,
    onRemove: (String) -> Unit,
    onRemoveGroup: (String) -> Unit,
    onClearAll: () -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onOpen: (DownloadEntry) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (DownloadEntry) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    groupTitle: (String) -> String = { it },
    artwork: DownloadArtwork = { _, tile -> DownloadArtworkPlaceholder(tile) },
    groupLink: GroupLinkFor = { null },
) {
    val groups = remember(downloads) { groupDownloads(downloads) }
    val open = groups.findByKey(openGroup)

    // The open group can empty out under the screen: its last item is removed here, or
    // "Clear all" is pressed elsewhere. Falling back to the listing is the only thing
    // left that makes sense, and the host's title bar follows because the state is its.
    LaunchedEffect(openGroup, open == null) {
        if (openGroup != null && open == null) onOpenGroup(null)
    }

    if (open != null) {
        GroupDownloads(
            group = open,
            title = open.id?.let(groupTitle) ?: stringResource(R.string.downloads_offline_ungrouped),
            onRemove = onRemove,
            onRemoveGroup = onRemoveGroup,
            onOpen = onOpen,
            onRetry = onRetry,
            modifier = modifier,
            contentPadding = contentPadding,
            artwork = artwork,
            groupLink = groupLink,
        )
    } else {
        GroupsLibrary(
            downloads = downloads,
            groups = groups,
            storageUsed = storageUsed,
            wifiOnly = wifiOnly,
            onOpenGroup = onOpenGroup,
            onRemove = onRemove,
            onClearAll = onClearAll,
            onToggleWifiOnly = onToggleWifiOnly,
            onRetry = onRetry,
            modifier = modifier,
            contentPadding = contentPadding,
            groupTitle = groupTitle,
            artwork = artwork,
        )
    }
}

// ---------------------------------------------------------------- level one

@Composable
private fun GroupsLibrary(
    downloads: List<DownloadEntry>,
    groups: List<DownloadGroup>,
    storageUsed: Long,
    wifiOnly: Boolean,
    onOpenGroup: (String?) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    groupTitle: (String) -> String,
    artwork: DownloadArtwork,
) {
    var confirmingClearAll by remember { mutableStateOf(false) }
    val transfers = remember(downloads) { activeTransfers(downloads) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = OfflineRowKeys.SUMMARY) {
            SummaryRow(
                storageUsed = storageUsed,
                itemCount = downloads.size,
                onClearAll = { confirmingClearAll = true },
            )
            WifiOnlyRow(wifiOnly = wifiOnly, onToggle = onToggleWifiOnly)
            HorizontalDivider()
        }

        // Everything that has not landed, in one place and clearly not part of the list
        // of groups. Filed away under its group it would be invisible, which for the one kind of
        // row a person is actively waiting on is the wrong way round.
        if (transfers.isNotEmpty()) {
            item(key = OfflineRowKeys.TRANSFERS_LABEL) { TransfersLabel() }
            items(
                items = transfers,
                // The download id survives every progress tick, so a queue for a
                // whole large group recomposes the one row that moved instead of
                // composing every row inside a single list item.
                key = { OfflineRowKeys.transfer(it.id) },
            ) { entry ->
                TransferGround(isLast = entry.id == transfers.last().id) {
                    TransferRow(entry = entry, onRemove = { onRemove(entry.id) }, onRetry = { onRetry(entry) })
                }
            }
        }

        if (groups.isEmpty()) {
            item(key = OfflineRowKeys.EMPTY) { EmptyState() }
        }

        // The row key is namespaced; the value handed to onOpenGroup is the group's own key.
        items(groups, key = { OfflineRowKeys.group(it) }) { group ->
            GroupRow(
                group = group,
                title = group.id?.let(groupTitle)
                    ?: stringResource(R.string.downloads_offline_ungrouped),
                onClick = { onOpenGroup(group.key) },
                artwork = artwork,
            )
        }
    }

    if (confirmingClearAll) {
        RemoveConfirmation(
            title = stringResource(R.string.downloads_offline_clear_all_title),
            bytes = storageUsed,
            onDismiss = { confirmingClearAll = false },
            onConfirm = {
                confirmingClearAll = false
                onClearAll()
            },
        )
    }
}

/**
 * One group, with its picture, its name and how much of the disk it is holding.
 *
 * The whole row is the target — there is no separate control on it — so nothing here can
 * delete anything. That is one screen further in, behind a question.
 */
@Composable
private fun GroupRow(
    group: DownloadGroup,
    title: String,
    onClick: () -> Unit,
    artwork: DownloadArtwork,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artwork(group.id, artworkModifier(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = groupSummary(group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Canvas(Modifier.size(20.dp)) { drawChevron(tint, rtl) }
    }
}

/**
 * The transfers strip's label, on the top end of the tonal ground the rows below
 * continue. It is a [Text] and nothing else: anything that looks like a heading is a
 * heading; the only controls are on the rows.
 *
 * The ground is split across the label, each row and the last row's rounded foot —
 * rather than one Surface around a Column — so the rows can be real lazy items and a
 * long queue never composes all at once. Adjacent items leave no gap, so the strip
 * still reads as one area.
 */
@Composable
private fun TransfersLabel() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Text(
            text = stringResource(R.string.downloads_offline_transfers),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
    }
}

/** One transfer row's share of the tonal ground; the last row carries the rounded foot. */
@Composable
private fun TransferGround(isLast: Boolean, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = if (isLast) 8.dp else 0.dp),
        shape = if (isLast) {
            RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        } else {
            androidx.compose.ui.graphics.RectangleShape
        },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(if (isLast) Modifier.padding(bottom = 8.dp) else Modifier) { content() }
    }
}

@Composable
private fun TransferRow(entry: DownloadEntry, onRemove: () -> Unit, onRetry: () -> Unit) {
    val failed = entry.state is DownloadState.Failed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!failed) {
            val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
            val fill = MaterialTheme.colorScheme.primary
            val progress = ringProgress(entry.state)
            Canvas(Modifier.size(18.dp)) { drawProgressRing(track, fill, progress, 0f) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
        if (failed) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.downloads_offline_retry))
            }
        }
        BinButton(
            contentDescription = stringResource(R.string.downloads_offline_remove_item),
            onClick = onRemove,
        )
    }
}

// ---------------------------------------------------------------- level two

@Composable
private fun GroupDownloads(
    group: DownloadGroup,
    title: String,
    onRemove: (String) -> Unit,
    onRemoveGroup: (String) -> Unit,
    onOpen: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    artwork: DownloadArtwork,
    groupLink: GroupLinkFor,
) {
    var confirmingRemoveAll by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = OfflineRowKeys.GROUP_HEADER) {
            GroupHeader(
                group = group,
                title = title,
                // Only ever offered for a real group: the loose bucket is not a group,
                // and "remove all of Other" is not a promise this screen can keep.
                onRemoveAll = group.id?.let { { confirmingRemoveAll = true } },
                artwork = artwork,
                groupLink = groupLink,
            )
            HorizontalDivider()
        }
        items(group.entries, key = { OfflineRowKeys.item(it.id) }) { entry ->
            ItemRow(
                entry = entry,
                group = group.id,
                onOpen = { onOpen(entry) },
                onRemove = { onRemove(entry.id) },
                onRetry = { onRetry(entry) },
                artwork = artwork,
            )
        }
    }

    if (confirmingRemoveAll) {
        RemoveConfirmation(
            title = stringResource(R.string.downloads_offline_remove_group_title),
            bytes = group.bytes,
            onDismiss = { confirmingRemoveAll = false },
            onConfirm = {
                confirmingRemoveAll = false
                group.id?.let(onRemoveGroup)
            },
        )
    }
}

/**
 * The open group, stated, and the two things that can be done with it.
 *
 * The order and the gap between them are the design: the one action that only ever
 * ADDS — go and look at the group — leads, filled and with its arrow, and the one
 * that deletes a hundred files is a quiet text button an obvious distance below it,
 * in the error colour. A bin where a thumb passes is easy to hit by accident; this puts the
 * safe action where the thumb lands and asks a question before the other one runs.
 */
@Composable
private fun GroupHeader(
    group: DownloadGroup,
    title: String,
    onRemoveAll: (() -> Unit)?,
    artwork: DownloadArtwork,
    groupLink: GroupLinkFor,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            artwork(group.id, artworkModifier(72.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = groupSummary(group),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        val link = group.id?.let { groupLink(it) }
        if (link != null) GoToGroupButton(link)
        if (onRemoveAll != null) {
            // The whole point of this gap: the two buttons are one safe tap and one
            // that deletes a group, and a slip must not be able to cross it.
            Spacer(Modifier.height(if (link == null) 4.dp else 20.dp))
            TextButton(
                onClick = onRemoveAll,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.downloads_offline_remove_group)) }
        }
    }
}

/**
 * "Go to group", as the host named it, with a forward chevron that turns around in
 * RTL — the same glyph and the same mirroring the group rows one level up use, so the
 * screen has one arrow rather than two that disagree.
 *
 * Disabled it keeps its supporting line, because a button that is off without saying
 * why reads as broken.
 */
@Composable
private fun GoToGroupButton(link: GroupLink) {
    Column(Modifier.padding(top = 10.dp)) {
        FilledTonalButton(
            onClick = link.onOpen,
            enabled = link.enabled,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            val tint = LocalContentColor.current
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Canvas(Modifier.size(18.dp)) { drawChevron(tint, rtl) }
            Spacer(Modifier.width(8.dp))
            Text(link.label)
        }
        if (link.supportingText != null) {
            Text(
                text = link.supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ItemRow(
    entry: DownloadEntry,
    group: String?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    artwork: DownloadArtwork,
) {
    val failed = entry.state is DownloadState.Failed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.state is DownloadState.Downloaded, onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artwork(group, artworkModifier(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
        if (failed) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.downloads_offline_retry))
            }
        }
        BinButton(
            contentDescription = stringResource(R.string.downloads_offline_remove_item),
            onClick = onRemove,
        )
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun SummaryRow(storageUsed: Long, itemCount: Int, onClearAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.downloads_offline_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.downloads_offline_storage_used, rememberFormattedBytes(storageUsed)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (itemCount > 0) {
            TextButton(onClick = onClearAll) {
                Text(stringResource(R.string.downloads_offline_clear_all))
            }
        }
    }
}

@Composable
private fun WifiOnlyRow(wifiOnly: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!wifiOnly) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.downloads_offline_wifi_only),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = wifiOnly, onCheckedChange = onToggle)
    }
}

/** "3 items · 120 MB", in the host's language and the host's plural rules. */
@Composable
private fun groupSummary(group: DownloadGroup): String = pluralStringResource(
    R.plurals.downloads_offline_group_summary,
    group.entries.size,
    group.entries.size,
    rememberFormattedBytes(group.bytes),
)

/** The one dialog shape both "remove everything" and "remove this group" wear. */
@Composable
private fun RemoveConfirmation(
    title: String,
    bytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val freed = rememberFormattedBytes(bytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(R.string.downloads_offline_clear_all_message, freed)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.downloads_offline_confirm_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.downloads_offline_cancel))
            }
        },
    )
}

/** Every tile in the listing: square, with the same corner as the cards around it. */
private fun artworkModifier(size: Dp): Modifier =
    Modifier.size(size).clip(RoundedCornerShape(8.dp))

@Composable
private fun statusLine(entry: DownloadEntry): String {
    val size = rememberFormattedBytes(entry.bytes)
    return when (val state = entry.state) {
        is DownloadState.Downloaded -> size
        is DownloadState.Downloading -> {
            val percent = contentDescriptionPercent(state)
            if (percent == null) size else "$size · $percent%"
        }
        is DownloadState.Failed -> stringResource(failureMessageRes(state.reason))
        DownloadState.Queued -> stringResource(R.string.downloads_download_state_queued)
        DownloadState.Removing -> stringResource(R.string.downloads_download_state_removing)
        DownloadState.NotDownloaded -> size
    }
}

@Composable
private fun BinButton(contentDescription: String, onClick: () -> Unit) {
    val tint = LocalContentColor.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawBin(tint) }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.downloads_offline_empty),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.downloads_offline_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
