package com.therapylocal.app.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.NetworkType

/**
 * Which network the model download may use (issue #55). The files are several GB, so they never come over mobile data, or any other metered
 * network, unless the user said yes to it after seeing the size. On Wi-Fi (unmetered) the download starts by itself, as before, but only
 * for an unmetered network: when Wi-Fi drops it pauses instead of carrying on over mobile data, and resumes once Wi-Fi is back.
 *
 * "Metered" is what Android reports ([NetworkCapabilities.NET_CAPABILITY_NOT_METERED] missing), the same test WorkManager uses for
 * [NetworkType.UNMETERED]: mobile data, and a Wi-Fi network the user marked as metered (a phone's hotspot usually is).
 */
object DownloadNetwork {
    enum class Kind { None, Unmetered, Metered }

    /** The answers to the question. [key] is what the log line `download_network_choice` carries. */
    enum class Choice(val key: String) { Now("now"), Wifi("wifi"), NotNow("not_now") }

    /** The default network now. */
    fun current(context: Context): Kind {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Kind.None
        val network = cm.activeNetwork ?: return Kind.None
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return Kind.None
        return kind(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    fun kind(hasInternet: Boolean, notMetered: Boolean): Kind = when {
        !hasInternet -> Kind.None
        notMetered -> Kind.Unmetered
        else -> Kind.Metered
    }

    /**
     * A download nobody chose a network for yet: on a metered network the user is asked first. With no network at all it is queued for
     * Wi-Fi without asking (whether the next network is metered is not known); the home line then offers mobile data.
     */
    fun mustAsk(network: Kind): Boolean = network == Kind.Metered

    /** The WorkManager constraint: [wifiOnly] waits for an unmetered network, otherwise any connection will do. */
    fun networkType(wifiOnly: Boolean): NetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    /** A queued download that is waiting because it may use only Wi-Fi, not for a connection as such. */
    fun waitingForWifi(wifiOnly: Boolean, network: Kind): Boolean = wifiOnly && network != Kind.Unmetered
}
