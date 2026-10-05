package com.fethica.downloadskit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The Wi-Fi-only flag has no silent default store: a config names the built-in
 * store's file, brings its own store, or does not construct at all. The foreground
 * notification id and the service every command goes to are checked here too.
 */
class DownloadsConfigTest {

    /** A host's own download service, declared in its manifest instead of the library's. */
    private class HostDownloadService : MediaDownloadService()

    private fun config(
        foregroundNotificationId: Int = MediaDownloadService.FOREGROUND_NOTIFICATION_ID,
    ) = DownloadsConfig(
        cacheDirName = "downloads",
        notificationSmallIcon = 0,
        preferencesName = "downloads",
        foregroundNotificationId = foregroundNotificationId,
    )

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

    @Test
    fun `a zero foreground notification id is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            config(foregroundNotificationId = 0)
        }
        assertEquals(
            "foregroundNotificationId must not be 0: media3 treats 0 as no foreground notification",
            error.message,
        )
    }

    @Test
    fun `a custom nonzero foreground notification id is kept`() {
        assertEquals(4210, config(foregroundNotificationId = 4210).foregroundNotificationId)
        assertEquals(-1, config(foregroundNotificationId = -1).foregroundNotificationId)
    }

    @Test
    fun `commands go to the library service by default`() {
        assertEquals(MediaDownloadService::class.java, config().serviceClass)
    }

    @Test
    fun `a host subclass of the service is accepted`() {
        val custom = DownloadsConfig(
            cacheDirName = "downloads",
            notificationSmallIcon = 0,
            preferencesName = "downloads",
            serviceClass = HostDownloadService::class.java,
        )
        assertEquals(HostDownloadService::class.java, custom.serviceClass)
    }
}
