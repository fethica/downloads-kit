package com.fethica.downloadskit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Title, subtitle, group and artwork ride in `DownloadRequest.data`, so the offline
 * screen can be drawn from the media3 index alone with no catalogue lookup.
 */
class DownloadMetadataTest {

    @Test
    fun `a full record round trips`() {
        val original = DownloadMetadata(
            title = "Item 7",
            subtitle = "Collection A",
            group = "group:42",
            artwork = byteArrayOf(1, 2, 3),
        )
        assertEquals(original, DownloadMetadata.decode(original.encode()))
    }

    @Test
    fun `null fields round trip as null and not as empty strings`() {
        val original = DownloadMetadata(title = "Solo", subtitle = null, group = null, artwork = null)
        val decoded = DownloadMetadata.decode(original.encode())
        assertEquals("Solo", decoded.title)
        assertNull(decoded.subtitle)
        assertNull(decoded.group)
        assertNull(decoded.artwork)
    }

    @Test
    fun `non ascii titles survive the round trip`() {
        val original = DownloadMetadata("عنوان تجريبي", "مجموعة", "group:1")
        assertEquals(original, DownloadMetadata.decode(original.encode()))
    }

    @Test
    fun `artwork bytes survive the round trip`() {
        val art = ByteArray(256) { (it and 0xFF).toByte() }
        val decoded = DownloadMetadata.decode(DownloadMetadata("t", null, null, art).encode())
        assertArrayEquals(art, decoded.artwork)
    }

    @Test
    fun `an empty blob decodes to an empty record instead of throwing`() {
        val decoded = DownloadMetadata.decode(ByteArray(0))
        assertEquals("", decoded.title)
        assertNull(decoded.group)
    }

    @Test
    fun `a blob written by something else decodes to an empty record instead of throwing`() {
        val decoded = DownloadMetadata.decode(byteArrayOf(9, 9, 9, 9, 9, 9))
        assertEquals("", decoded.title)
        assertNull(decoded.group)
    }

    @Test
    fun `equality is by value including the artwork bytes`() {
        assertEquals(
            DownloadMetadata("t", "s", "g", byteArrayOf(1)),
            DownloadMetadata("t", "s", "g", byteArrayOf(1)),
        )
    }
}
