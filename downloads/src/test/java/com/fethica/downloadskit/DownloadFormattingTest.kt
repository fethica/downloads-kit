package com.fethica.downloadskit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Size formatting: base 1000, one decimal from MB up, locale-aware separators and units. */
class DownloadFormattingTest {

    private val english = Locale.ENGLISH

    @Test
    fun `zero and small values are plain bytes`() {
        assertEquals("0 B", formatBytes(0, english))
        assertEquals("999 B", formatBytes(999, english))
    }

    @Test
    fun `negative values are treated as zero`() {
        assertEquals("0 B", formatBytes(-1, english))
    }

    @Test
    fun `kilobytes are whole numbers`() {
        assertEquals("1 KB", formatBytes(1_000, english))
        assertEquals("512 KB", formatBytes(512_000, english))
        assertEquals("1000 KB", formatBytes(999_999, english))
    }

    @Test
    fun `megabytes carry one decimal`() {
        assertEquals("1.0 MB", formatBytes(1_000_000, english))
        assertEquals("8.4 MB", formatBytes(8_400_000, english))
    }

    @Test
    fun `gigabytes carry one decimal`() {
        assertEquals("1.0 GB", formatBytes(1_000_000_000, english))
        assertEquals("2.5 GB", formatBytes(2_500_000_000, english))
    }

    @Test
    fun `the decimal separator follows the locale`() {
        assertEquals("8,4 MB", formatBytes(8_400_000, Locale.FRENCH))
    }

    @Test
    fun `the unit labels are supplied by the caller so they can come from resources`() {
        val french = ByteUnits(bytes = "o", kilobytes = "Ko", megabytes = "Mo", gigabytes = "Go")
        assertEquals("8,4 Mo", formatBytes(8_400_000, Locale.FRENCH, french))
        assertEquals("12 Ko", formatBytes(12_000, Locale.FRENCH, french))
    }

    @Test
    fun `terabyte scale values stay in gigabytes rather than inventing a unit`() {
        assertEquals("1500.0 GB", formatBytes(1_500_000_000_000, english))
    }
}
