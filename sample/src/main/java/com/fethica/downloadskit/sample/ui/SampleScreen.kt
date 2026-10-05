@file:OptIn(ExperimentalMaterial3Api::class)

package com.fethica.downloadskit.sample.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fethica.downloadskit.sample.SampleController
import com.fethica.downloadskit.sample.stateOf
import com.fethica.downloadskit.DownloadState
import com.fethica.downloadskit.compose.OfflineButton
import com.fethica.downloadskit.compose.OfflineScreen
import com.fethica.downloadskit.compose.rememberFormattedBytes
import com.fethica.downloadskit.sample.fixtures.WavFixture

/**
 * One screen, three tabs: the fixture items with their controls, the grouped library
 * drawn by the downloads-compose `OfflineScreen`, and the diagnostics panel. Plain
 * Material 3 with the default colour schemes; no design system of its own.
 */
@Composable
internal fun SampleScreen(sample: SampleController) {
    val entries by sample.downloads.all().collectAsState(initial = emptyList())
    val storageUsed by sample.downloads.storageUsed().collectAsState(initial = 0L)
    val wifiOnly by sample.downloads.wifiOnly.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Downloads sample",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> FixturesTab(
                    sample = sample,
                    states = sample.fixtures.associate { it.name to entries.stateOf(it.name) },
                    wifiOnly = wifiOnly,
                )
                1 -> {
                    BackHandler(enabled = openGroup != null) { openGroup = null }
                    OfflineScreen(
                        downloads = entries,
                        storageUsed = storageUsed,
                        wifiOnly = wifiOnly,
                        openGroup = openGroup,
                        onOpenGroup = { openGroup = it },
                        onRemove = sample::remove,
                        onRemoveGroup = sample::removeGroup,
                        onClearAll = sample::clearAll,
                        onToggleWifiOnly = sample::setWifiOnly,
                        // No playback in this sample: opening a downloaded item runs its
                        // SHA-256 proof and shows the result.
                        onOpen = { entry ->
                            sample.fixture(entry.id)?.let(sample::verify)
                            tab = 2
                        },
                        onRetry = { entry -> sample.fixture(entry.id)?.let(sample::retry) },
                        groupTitle = sample::groupTitle,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> DiagnosticsPanel(sample = sample, entries = entries)
            }
        }
    }
}

private val TABS = listOf("Fixtures", "Library", "Diagnostics")

@Composable
private fun FixturesTab(
    sample: SampleController,
    states: Map<String, DownloadState>,
    wifiOnly: Boolean,
) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Library setting: requires a network that is not metered.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = wifiOnly, onCheckedChange = sample::setWifiOnly)
            }
        }
        items(sample.fixtures, key = { it.name }) { fixture ->
            FixtureCard(
                fixture = fixture,
                state = states[fixture.name] ?: DownloadState.NotDownloaded,
                onStart = { sample.start(fixture) },
                onRetry = { sample.retry(fixture) },
                onRemove = { sample.remove(fixture.name) },
            )
        }
        item {
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = states.values.any { it != DownloadState.NotDownloaded },
            ) { Text("Clear all") }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all downloads?") },
            text = { Text("Removes every fixture download of this sample, including partial ones.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    sample.clearAll()
                }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FixtureCard(
    fixture: WavFixture,
    state: DownloadState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The library's own per-item control: tap to download, to retry a
                // failure, or to remove what is queued, running or done.
                OfflineButton(
                    state = state,
                    onClick = when (state) {
                        DownloadState.NotDownloaded -> onStart
                        is DownloadState.Failed -> onRetry
                        DownloadState.Removing -> ({})
                        else -> onRemove
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(fixture.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${rememberFormattedBytes(fixture.sizeBytes.toLong())} · ${statusText(state, fixture)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            when (state) {
                is DownloadState.Downloading ->
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                DownloadState.Queued -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                else -> Unit
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onStart, enabled = state == DownloadState.NotDownloaded) { Text("Start") }
                TextButton(onClick = onRetry, enabled = state is DownloadState.Failed) { Text("Retry") }
                TextButton(
                    onClick = onRemove,
                    enabled = state != DownloadState.NotDownloaded && state != DownloadState.Removing,
                ) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun statusText(state: DownloadState, fixture: WavFixture): String = when (state) {
    DownloadState.NotDownloaded -> "Not downloaded"
    DownloadState.Queued -> "Queued: waiting for a slot or for the network requirement"
    is DownloadState.Downloading -> if (state.progress >= 0f) {
        "Downloading ${(state.progress * 100).toInt()} % (${rememberFormattedBytes(state.bytes)} of " +
            "${rememberFormattedBytes(fixture.sizeBytes.toLong())})"
    } else {
        "Downloading ${rememberFormattedBytes(state.bytes)}"
    }
    is DownloadState.Downloaded -> "Downloaded (${rememberFormattedBytes(state.bytes)})"
    is DownloadState.Failed -> "Failed: ${state.reason.key}"
    DownloadState.Removing -> "Removing"
}
