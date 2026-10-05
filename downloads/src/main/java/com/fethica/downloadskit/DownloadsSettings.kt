package com.fethica.downloadskit

import android.content.Context

/**
 * Where the Wi-Fi-only preference lives. The library holds it in memory and hands the
 * writes to the host, so it sits alongside the host's other settings rather than in a
 * private store of its own.
 */
public interface DownloadsSettings {
    public fun getWifiOnly(): Boolean
    public fun setWifiOnly(enabled: Boolean)

    public companion object {
        /** For tests and previews. Nothing survives a process restart. */
        public fun inMemory(initial: Boolean = true): DownloadsSettings = object : DownloadsSettings {
            private var value = initial
            override fun getWifiOnly(): Boolean = value
            override fun setWifiOnly(enabled: Boolean) {
                value = enabled
            }
        }

        /**
         * A ready-made store, for a host with nowhere better to put it.
         *
         * Constructing one touches nothing: a host builds this in `Application.onCreate`,
         * and the first `getSharedPreferences` for a name parses the whole XML file on the
         * calling thread. The store is opened on the first read instead, which
         * [DownloadsCore] does on a dispatcher.
         */
        public fun sharedPreferences(
            context: Context,
            name: String,
            default: Boolean = true,
        ): DownloadsSettings {
            val appContext = context.applicationContext
            return object : DownloadsSettings {
                private val prefs by lazy { appContext.getSharedPreferences(name, Context.MODE_PRIVATE) }
                override fun getWifiOnly(): Boolean = prefs.getBoolean(KEY_WIFI_ONLY, default)
                override fun setWifiOnly(enabled: Boolean) {
                    prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
                }
            }
        }

        private const val KEY_WIFI_ONLY = "wifi_only"
    }
}
