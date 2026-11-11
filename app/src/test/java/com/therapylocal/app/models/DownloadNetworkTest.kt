package com.therapylocal.app.models

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model download never uses mobile data without asking (issue #55). */
class DownloadNetworkTest {
    @Test
    fun androidCapabilitiesMapToTheThreeKinds() {
        assertEquals(DownloadNetwork.Kind.Unmetered, DownloadNetwork.kind(hasInternet = true, notMetered = true))
        assertEquals(DownloadNetwork.Kind.Metered, DownloadNetwork.kind(hasInternet = true, notMetered = false))
        assertEquals(DownloadNetwork.Kind.None, DownloadNetwork.kind(hasInternet = false, notMetered = true))
        assertEquals(DownloadNetwork.Kind.None, DownloadNetwork.kind(hasInternet = false, notMetered = false))
    }

    @Test
    fun onlyAMeteredNetworkAsksFirst() {
        assertTrue(DownloadNetwork.mustAsk(DownloadNetwork.Kind.Metered))
        assertFalse(DownloadNetwork.mustAsk(DownloadNetwork.Kind.Unmetered))
        assertFalse(DownloadNetwork.mustAsk(DownloadNetwork.Kind.None))
    }

    @Test
    fun wifiOnlyWorkWaitsForAnUnmeteredNetwork() {
        assertEquals(NetworkType.UNMETERED, DownloadNetwork.networkType(wifiOnly = true))
        assertEquals(NetworkType.CONNECTED, DownloadNetwork.networkType(wifiOnly = false))
    }

    @Test
    fun waitingForWifiOnlyWhenTheWorkIsWifiOnlyAndWifiIsGone() {
        assertTrue(DownloadNetwork.waitingForWifi(wifiOnly = true, network = DownloadNetwork.Kind.Metered))
        assertTrue(DownloadNetwork.waitingForWifi(wifiOnly = true, network = DownloadNetwork.Kind.None))
        assertFalse(DownloadNetwork.waitingForWifi(wifiOnly = true, network = DownloadNetwork.Kind.Unmetered))
        // "Download now" (any network): a wait is for a connection, never for Wi-Fi.
        assertFalse(DownloadNetwork.waitingForWifi(wifiOnly = false, network = DownloadNetwork.Kind.Metered))
        assertFalse(DownloadNetwork.waitingForWifi(wifiOnly = false, network = DownloadNetwork.Kind.None))
    }

    @Test
    fun choicesLogStableKeys() {
        assertEquals(listOf("now", "wifi", "not_now"), DownloadNetwork.Choice.entries.map { it.key })
    }
}
