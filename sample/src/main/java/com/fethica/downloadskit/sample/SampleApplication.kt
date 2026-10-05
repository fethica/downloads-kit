package com.fethica.downloadskit.sample

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.fethica.downloadskit.DownloadsConfig
import com.fethica.downloadskit.MediaDownloads

/**
 * Installs the downloader exactly as a host app would, with storage names, a channel,
 * an icon and a notification id that belong to this sample alone.
 */
class SampleApplication : Application() {

    internal lateinit var sample: SampleController
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val downloads = MediaDownloads.install(
            this,
            DownloadsConfig(
                cacheDirName = SampleController.CACHE_DIR,
                notificationSmallIcon = R.drawable.ic_stat_sample_download,
                preferencesName = SampleController.SETTINGS_PREFS,
                notificationChannelId = SampleController.CHANNEL_ID,
                userAgent = "DownloadsKitSample/${BuildConfig.VERSION_NAME}",
                // No scheduler: Media3 would use it below API 31 only, and a sample has no
                // business holding a JobScheduler id.
                schedulerJobId = null,
                notificationContentIntent = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
                foregroundNotificationId = SampleController.NOTIFICATION_ID,
            ),
        )
        sample = SampleController(this, downloads).also { it.start() }
    }

    /** The library never creates a channel; the host does, before installing. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            SampleController.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
