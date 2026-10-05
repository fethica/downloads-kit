package com.fethica.downloadskit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.fethica.downloadskit.ByteUnits
import com.fethica.downloadskit.compose.R
import com.fethica.downloadskit.formatBytes
import java.util.Locale

/** The size units in the current configuration's language. */
@Composable
public fun rememberByteUnits(): ByteUnits {
    val bytes = stringResource(R.string.downloads_unit_bytes)
    val kilobytes = stringResource(R.string.downloads_unit_kilobytes)
    val megabytes = stringResource(R.string.downloads_unit_megabytes)
    val gigabytes = stringResource(R.string.downloads_unit_gigabytes)
    return remember(bytes, kilobytes, megabytes, gigabytes) {
        ByteUnits(bytes, kilobytes, megabytes, gigabytes)
    }
}

/** [formatBytes] with the units and separators of the current configuration. */
@Composable
public fun rememberFormattedBytes(bytes: Long): String {
    val units = rememberByteUnits()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(bytes, units, locale) { formatBytes(bytes, locale, units) }
}
