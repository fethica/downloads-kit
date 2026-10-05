package com.fethica.downloadskit

import java.util.Locale

/**
 * The unit labels [formatBytes] appends. Defaults are English; the Compose kit passes
 * the localized set from the library's own string resources.
 */
public data class ByteUnits(
    val bytes: String,
    val kilobytes: String,
    val megabytes: String,
    val gigabytes: String,
) {
    public companion object {
        public val DEFAULT: ByteUnits = ByteUnits(
            bytes = "B",
            kilobytes = "KB",
            megabytes = "MB",
            gigabytes = "GB",
        )
    }
}

/**
 * Formats a size the way a storage screen does: base 1000, whole numbers up to
 * kilobytes, one decimal from megabytes up, separators from [locale].
 */
public fun formatBytes(
    bytes: Long,
    locale: Locale = Locale.getDefault(),
    units: ByteUnits = ByteUnits.DEFAULT,
): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe < KILO -> String.format(locale, "%d %s", safe, units.bytes)
        safe < MEGA -> String.format(locale, "%d %s", Math.round(safe / KILO.toDouble()), units.kilobytes)
        safe < GIGA -> String.format(locale, "%.1f %s", safe / MEGA.toDouble(), units.megabytes)
        else -> String.format(locale, "%.1f %s", safe / GIGA.toDouble(), units.gigabytes)
    }
}

private const val KILO = 1_000L
private const val MEGA = 1_000_000L
private const val GIGA = 1_000_000_000L
