package com.fethica.downloadskit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The Wi-Fi-only flag has no silent default store: a config names the built-in
 * store's file, brings its own store, or does not construct at all.
 */
class DownloadsConfigTest {

    @Test
    fun `a config needs a preferences name or its own settings`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DownloadsConfig(cacheDirName = "downloads", notificationSmallIcon = 0)
        }
        assertEquals(
            "pass preferencesName for the built-in SharedPreferences store, or settings for your own",
            error.message,
        )

        val named = DownloadsConfig(
            cacheDirName = "downloads",
            notificationSmallIcon = 0,
            preferencesName = "downloads",
        )
        assertEquals("downloads", named.preferencesName)

        val own = DownloadsSettings.inMemory()
        val custom = DownloadsConfig(
            cacheDirName = "downloads",
            notificationSmallIcon = 0,
            settings = own,
        )
        assertSame(own, custom.settings)
    }
}
