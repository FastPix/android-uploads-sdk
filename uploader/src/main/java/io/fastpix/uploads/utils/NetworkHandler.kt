package io.fastpix.uploads.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.os.Build

class NetworkHandler constructor(private val context: Context) {

    interface NetworkListener {
        fun onAvailable()
        fun onLost()
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var listener: NetworkListener? = null

    fun setNetworkListener(listener: NetworkListener) {
        this.listener = listener
        register()
    }

    private fun register() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        listener?.onAvailable()
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        listener?.onLost()
                    }
                })
        } else {
            // Fallback for older versions
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val activeNetwork = connectivityManager.activeNetworkInfo
                    if (activeNetwork != null && activeNetwork.isConnected) {
                        listener?.onAvailable()
                    } else {
                        listener?.onLost()
                    }
                }
            }, filter)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: NetworkHandler? = null

        fun getInstance(context: Context): NetworkHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkHandler(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}