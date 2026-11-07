package com.dari.screentranslator.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide connectivity watch. Registers one system callback and exposes the
 * result as a StateFlow so the UI can react to the connection coming and
 * going — e.g. resuming the one-time offline-pack download the moment the
 * user gets internet.
 *
 * Lives beside [SystemUtils] because both wrap ConnectivityManager.
 */
object NetworkMonitor {

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile
    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        val connectivity =
            appContext.getSystemService(ConnectivityManager::class.java) ?: return
        registered = true
        _isOnline.value = SystemUtils.isOnline(appContext)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
            }

            override fun onLost(network: Network) {
                _isOnline.value = SystemUtils.isOnline(appContext)
            }
        }
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (t: Throwable) {
            // Some devices throw when a callback is already registered for
            // this process; the snapshot taken above still covers us.
            registered = false
        }
    }
}
