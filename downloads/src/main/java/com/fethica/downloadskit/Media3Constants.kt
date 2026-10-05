package com.fethica.downloadskit

/**
 * The media3 integer constants this library maps, copied so the mapping stays a pure
 * function that a JVM unit test can drive without loading an Android class.
 *
 * Verified against `androidx.media3:media3-exoplayer:1.9.2` (`Download`,
 * `Requirements`) and `androidx.media3:media3-common:1.9.2` (`C.LENGTH_UNSET`).
 * `Media3ConstantsTest`, a JVM unit test, pins them to the real thing.
 */
internal object Media3 {
    const val STATE_QUEUED: Int = 0
    const val STATE_STOPPED: Int = 1
    const val STATE_DOWNLOADING: Int = 2
    const val STATE_COMPLETED: Int = 3
    const val STATE_FAILED: Int = 4
    const val STATE_REMOVING: Int = 5
    const val STATE_RESTARTING: Int = 7

    const val FAILURE_REASON_NONE: Int = 0
    const val FAILURE_REASON_UNKNOWN: Int = 1

    const val STOP_REASON_NONE: Int = 0

    const val REQUIREMENT_NETWORK: Int = 1
    const val REQUIREMENT_NETWORK_UNMETERED: Int = 1 shl 1
    const val REQUIREMENT_DEVICE_IDLE: Int = 1 shl 2
    const val REQUIREMENT_DEVICE_CHARGING: Int = 1 shl 3
    const val REQUIREMENT_DEVICE_STORAGE_NOT_LOW: Int = 1 shl 4

    const val LENGTH_UNSET: Long = -1L
    const val PERCENTAGE_UNSET: Float = -1f
}
