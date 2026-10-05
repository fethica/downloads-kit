package com.fethica.downloadskit

/**
 * The Wi-Fi-only flag as a media3 `Requirements` bit mask.
 *
 * `NETWORK_UNMETERED` is always paired with `NETWORK`: media3 does the same widening
 * internally, and stating it here keeps the mask readable in a log.
 */
internal fun requirementsMask(wifiOnly: Boolean): Int = if (wifiOnly) {
    Media3.REQUIREMENT_NETWORK or Media3.REQUIREMENT_NETWORK_UNMETERED
} else {
    Media3.REQUIREMENT_NETWORK
}

internal fun isWifiOnly(mask: Int): Boolean = mask and Media3.REQUIREMENT_NETWORK_UNMETERED != 0
