package com.fethica.downloadskit.sample

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Structured one-line events under a fixed tag, for `adb logcat -s DownloadsKitSample`.
 *
 * Every line is `ts=<UTC ISO-8601> item=<id> state=<state> [detail]`. Items are fixture
 * names; the only address that may appear in a detail is the loopback host and port.
 * No full URL is logged, so nothing private can leak through the log even if the sample is
 * pointed somewhere else later.
 */
internal object SampleLog {
    const val TAG = "DownloadsKitSample"

    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun event(item: String, state: String, detail: String? = null) {
        val ts = format.get()!!.format(Date())
        val line = buildString {
            append("ts=").append(ts).append(" item=").append(item).append(" state=").append(state)
            if (!detail.isNullOrBlank()) append(' ').append(detail)
        }
        Log.d(TAG, line)
    }
}
