package com.fethica.downloadskit

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

/**
 * The foreground service that does the transferring. Declared by the library's own
 * manifest, so a host has nothing to add: installing [MediaDownloads] in `Application`
 * is the whole wiring.
 *
 * The notification is built with the host's channel, small icon and content intent from
 * [DownloadsConfig]; the library never creates a channel of its own.
 */
@OptIn(UnstableApi::class)
public open class MediaDownloadService : DownloadService(
    // The platform builds the service after Application.onCreate, so the installed
    // config is already there; the constant covers a service started without one.
    MediaDownloads.get()?.config?.foregroundNotificationId ?: FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
) {
    override fun getDownloadManager(): DownloadManager = MediaDownloads.required().downloadManager

    override fun getScheduler(): Scheduler? = MediaDownloads.required().scheduler

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        val media = MediaDownloads.required()
        return media.notificationHelper.buildProgressNotification(
            this,
            media.config.notificationSmallIcon,
            media.config.notificationContentIntent,
            null,
            downloads,
            notMetRequirements,
        )
    }

    public companion object {
        /** The default for [DownloadsConfig.foregroundNotificationId]. */
        public const val FOREGROUND_NOTIFICATION_ID: Int = 7301
    }
}
