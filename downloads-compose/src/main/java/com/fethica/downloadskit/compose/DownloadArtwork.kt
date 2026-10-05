package com.fethica.downloadskit.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * How a host draws the artwork of one group.
 *
 * The library has no image loader and must not gain one: a host already has the one it
 * uses everywhere else, with its own disk cache, its own fallback chain and its own idea
 * of what a missing picture looks like. It is handed the group — the same opaque key it
 * filed the download under — and draws into the [Modifier] it is given, which already
 * carries the size and the corner clip the listing wants.
 *
 * The group is null for the bucket of downloads filed without one.
 */
public typealias DownloadArtwork = @Composable (group: String?, modifier: Modifier) -> Unit

/**
 * The tile a listing draws when the host has no picture for a group, and the default
 * when no host draws artwork at all.
 *
 * A tinted square with a quiet level meter in it, rather than a hole: a row that keeps
 * its shape when the picture is missing is the only version of this that does not make
 * the whole list jump. The glyph takes a FRACTION of the tile rather than a size of its
 * own, so it reads the same on a 44dp item row and a 72dp group header.
 */
@Composable
public fun DownloadArtworkPlaceholder(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize(0.5f)) { drawLevels(tint) }
    }
}
