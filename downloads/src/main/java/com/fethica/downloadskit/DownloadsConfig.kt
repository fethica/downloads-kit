package com.fethica.downloadskit

import android.app.PendingIntent
import androidx.annotation.DrawableRes
import androidx.media3.datasource.HttpDataSource

/**
 * What a host has to decide once, in `Application.onCreate`.
 *
 * The notification channel is the host's: the library never creates one, so the
 * download notification sits in the same channel group as the host's other ones and
 * follows whatever importance the host chose.
 *
 * The storage names have no default on purpose. They name where a host's downloads
 * already live on its users' phones, and a default that is wrong for one host silently
 * strands every file and setting it has stored. So [cacheDirName] is required, and the
 * Wi-Fi-only flag needs either [preferencesName] or [settings]: constructing with
 * neither throws.
 *
 * @param cacheDirName a folder under the app's private files dir; no storage permission.
 * @param notificationSmallIcon the status bar icon for the progress notification.
 * @param preferencesName the `SharedPreferences` file of the built-in store for the
 *   Wi-Fi-only flag ([DownloadsSettings.sharedPreferences]). Used only when [settings]
 *   is null.
 * @param settings the host's own store for the Wi-Fi-only flag. Wins over
 *   [preferencesName] when both are given.
 * @param notificationChannelId an existing channel the host has already created.
 * @param userAgent sent with every download request; ignored if [httpDataSourceFactory] is set.
 * @param httpDataSourceFactory the host's own HTTP stack, when it has one worth reusing.
 * @param maxParallelDownloads how many items transfer at once.
 * @param schedulerJobId a `JobScheduler` id, used BELOW API 31 only: media3 asks for a
 *   scheduler when `Build.VERSION.SDK_INT < 31` and ignores it above, so on Android 12 and
 *   later downloads do not resume by themselves after a reboot. What resumes a restored
 *   queue on every version is the next launch, which starts the download service itself.
 *   Null, the default, schedules nothing.
 * @param notificationContentIntent where tapping the progress notification goes.
 * @param foregroundNotificationId the id of the download service's foreground
 *   notification; change it only if it collides with one of the host's own. Must not be
 *   0: media3 reads 0 as "no foreground notification" and would then never promote the
 *   service to the foreground, so constructing with 0 throws.
 * @param serviceClass the download service every command is sent to. The default is the
 *   library's own [MediaDownloadService], which its manifest already declares. A host that
 *   declares its own subclass instead passes that subclass here, and removes the library's
 *   manifest entry with `tools:node="remove"` so exactly one download service is declared.
 */
public class DownloadsConfig(
    public val cacheDirName: String,
    @param:DrawableRes public val notificationSmallIcon: Int,
    public val preferencesName: String? = null,
    public val settings: DownloadsSettings? = null,
    public val notificationChannelId: String = DEFAULT_NOTIFICATION_CHANNEL_ID,
    public val userAgent: String? = null,
    public val httpDataSourceFactory: HttpDataSource.Factory? = null,
    public val maxParallelDownloads: Int = 2,
    public val schedulerJobId: Int? = null,
    public val notificationContentIntent: PendingIntent? = null,
    public val foregroundNotificationId: Int = MediaDownloadService.FOREGROUND_NOTIFICATION_ID,
    public val serviceClass: Class<out MediaDownloadService> = MediaDownloadService::class.java,
) {
    init {
        require(settings != null || preferencesName != null) {
            "pass preferencesName for the built-in SharedPreferences store, or settings for your own"
        }
        require(foregroundNotificationId != 0) {
            "foregroundNotificationId must not be 0: media3 treats 0 as no foreground notification"
        }
    }

    public companion object {
        /** The channel used when the host names none. */
        public const val DEFAULT_NOTIFICATION_CHANNEL_ID: String = "downloads"
    }
}
