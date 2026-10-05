package com.fethica.downloadskit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wi-Fi-only flag, translated into the media3 `Requirements` bit mask. */
class DownloadRequirementsTest {

    @Test
    fun `wifi only requires an unmetered network`() {
        val mask = requirementsMask(wifiOnly = true)
        assertTrue(mask and Media3.REQUIREMENT_NETWORK_UNMETERED != 0)
    }

    @Test
    fun `wifi only still requires a network at all so a metered link is not treated as met`() {
        val mask = requirementsMask(wifiOnly = true)
        assertTrue(mask and Media3.REQUIREMENT_NETWORK != 0)
    }

    @Test
    fun `off wifi only requires any network`() {
        assertEquals(Media3.REQUIREMENT_NETWORK, requirementsMask(wifiOnly = false))
    }

    @Test
    fun `nothing but network requirements are ever asked for`() {
        val everythingElse = Media3.REQUIREMENT_DEVICE_IDLE or
            Media3.REQUIREMENT_DEVICE_CHARGING or
            Media3.REQUIREMENT_DEVICE_STORAGE_NOT_LOW
        assertEquals(0, requirementsMask(wifiOnly = true) and everythingElse)
        assertEquals(0, requirementsMask(wifiOnly = false) and everythingElse)
    }

    @Test
    fun `the mask round trips through the flag`() {
        assertTrue(isWifiOnly(requirementsMask(wifiOnly = true)))
        assertFalse(isWifiOnly(requirementsMask(wifiOnly = false)))
    }
}
