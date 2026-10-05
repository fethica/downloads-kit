package com.fethica.downloadskit

import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The library copies media3's integer constants so the mapping stays pure. This pins the
 * copies to the real thing: if media3 ever renumbers, this fails instead of the mapping
 * silently going wrong.
 */
class Media3ConstantsTest {

    @Test
    fun `download states match media3`() {
        assertEquals(Download.STATE_QUEUED, Media3.STATE_QUEUED)
        assertEquals(Download.STATE_STOPPED, Media3.STATE_STOPPED)
        assertEquals(Download.STATE_DOWNLOADING, Media3.STATE_DOWNLOADING)
        assertEquals(Download.STATE_COMPLETED, Media3.STATE_COMPLETED)
        assertEquals(Download.STATE_FAILED, Media3.STATE_FAILED)
        assertEquals(Download.STATE_REMOVING, Media3.STATE_REMOVING)
        assertEquals(Download.STATE_RESTARTING, Media3.STATE_RESTARTING)
    }

    @Test
    fun `failure and stop reasons match media3`() {
        assertEquals(Download.FAILURE_REASON_NONE, Media3.FAILURE_REASON_NONE)
        assertEquals(Download.FAILURE_REASON_UNKNOWN, Media3.FAILURE_REASON_UNKNOWN)
        assertEquals(Download.STOP_REASON_NONE, Media3.STOP_REASON_NONE)
    }

    @Test
    fun `requirement flags match media3`() {
        assertEquals(Requirements.NETWORK, Media3.REQUIREMENT_NETWORK)
        assertEquals(Requirements.NETWORK_UNMETERED, Media3.REQUIREMENT_NETWORK_UNMETERED)
        assertEquals(Requirements.DEVICE_IDLE, Media3.REQUIREMENT_DEVICE_IDLE)
        assertEquals(Requirements.DEVICE_CHARGING, Media3.REQUIREMENT_DEVICE_CHARGING)
        assertEquals(Requirements.DEVICE_STORAGE_NOT_LOW, Media3.REQUIREMENT_DEVICE_STORAGE_NOT_LOW)
    }
}
