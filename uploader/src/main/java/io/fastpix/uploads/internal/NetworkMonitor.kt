package io.fastpix.uploads.internal

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Per-uploader connectivity monitor. Owns a single [ConnectivityManager.NetworkCallback]
 * registered at [start] and unregistered at [stop]. Unlike the previous singleton
 * implementation this never leaks callbacks across upload sessions.
 *
 * Multi-transport correctness: the framework fires onAvailable/onLost *per network*, so
 * a device with both WiFi and cellular receives two onAvailable callbacks at startup and
 * an isolated onLost when either transport drops. This monitor tracks the active set of
 * matching networks and only escalates to the listener on the empty↔non-empty transition,
 * so the engine sees a single "online" boolean rather than one signal per transport.
 */
internal class NetworkMonitor(context: Context) {

    interface Listener {
        fun onAvailable()
        fun onLost()
    }

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val lock = Any()
    private val activeNetworks = HashSet<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * True if the active default network reports INTERNET capability.
     *
     * Deliberately does NOT require NET_CAPABILITY_VALIDATED: that capability is only
     * set after ConnectivityManager's background probe to Google's connectivitycheck
     * endpoint succeeds, which can take seconds on a fresh connection and may never
     * succeed on networks where that specific endpoint is blocked (corporate Wi-Fi,
     * some carriers, captive-portal networks). Requiring VALIDATED here makes the SDK
     * report "offline" on working networks.
     */
    @SuppressLint("MissingPermission")
    fun isOnline(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        synchronized(lock) {
            if (callback != null) return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val crossedEmptyToNonEmpty: Boolean
                synchronized(lock) {
                    val wasEmpty = activeNetworks.isEmpty()
                    activeNetworks.add(network)
                    crossedEmptyToNonEmpty = wasEmpty && activeNetworks.isNotEmpty()
                }
                if (crossedEmptyToNonEmpty) listener.onAvailable()
            }

            override fun onLost(network: Network) {
                val crossedNonEmptyToEmpty: Boolean
                synchronized(lock) {
                    val wasNonEmpty = activeNetworks.isNotEmpty()
                    activeNetworks.remove(network)
                    crossedNonEmptyToEmpty = wasNonEmpty && activeNetworks.isEmpty()
                }
                if (crossedNonEmptyToEmpty) listener.onLost()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, cb)
        synchronized(lock) {
            callback = cb
        }
    }

    fun stop() {
        val cb = synchronized(lock) {
            val current = callback ?: return
            callback = null
            activeNetworks.clear()
            current
        }
        runCatching { connectivity.unregisterNetworkCallback(cb) }
    }
}
