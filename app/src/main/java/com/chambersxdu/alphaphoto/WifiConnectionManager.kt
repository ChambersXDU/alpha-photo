package com.chambersxdu.alphaphoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

data class CameraNetwork(
    val network: Network,
    val gateway: InetAddress,
)

class WifiConnectionManager(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var deliveredNetwork: Network? = null

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    fun connect(
        credentials: CameraWifiCredentials,
        onStatus: (String) -> Unit,
        onConnected: (CameraNetwork) -> Unit,
        onError: (String) -> Unit,
    ) {
        check(networkCallback == null)

        if (!wifiManager.isWifiEnabled) {
            onError("Phone Wi-Fi is off.")
            return
        }

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setWpa2Passphrase(credentials.password)

        credentials.ssid?.let(specifierBuilder::setSsid)
        credentials.bssid?.let { bssid ->
            specifierBuilder.setBssid(MacAddress.fromString(bssid))
        }

        val specifier = specifierBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Camera Wi-Fi network available network=$network")
                post(onStatus, "Camera Wi-Fi connected.")
                deliverWhenReady(network, onConnected)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                deliverWhenReady(network, onConnected, linkProperties)
            }

            override fun onUnavailable() {
                Log.e(TAG, "Camera Wi-Fi network request unavailable")
                post(onError, "Android could not join the camera Wi-Fi.")
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Camera Wi-Fi network lost network=$network")
                if (deliveredNetwork == network) {
                    deliveredNetwork = null
                }
                post(onStatus, "Camera Wi-Fi disconnected.")
            }
        }

        networkCallback = callback
        Log.i(
            TAG,
            "Requesting camera Wi-Fi ssid=${credentials.ssid} bssid=${credentials.bssid}",
        )
        post(onStatus, "Joining camera Wi-Fi…")
        connectivityManager.requestNetwork(request, callback)
    }

    fun release() {
        val callback = networkCallback ?: return
        connectivityManager.unregisterNetworkCallback(callback)
        networkCallback = null
        deliveredNetwork = null
    }

    private fun deliverWhenReady(
        network: Network,
        onConnected: (CameraNetwork) -> Unit,
        linkProperties: LinkProperties? = connectivityManager.getLinkProperties(network),
    ) {
        if (deliveredNetwork == network) {
            return
        }

        val gateway = linkProperties
            ?.routes
            ?.firstOrNull { route ->
                route.isDefaultRoute && route.gateway is Inet4Address
            }
            ?.gateway
            ?: return

        deliveredNetwork = network
        Log.i(TAG, "Camera Wi-Fi gateway=${gateway.hostAddress}")
        post(onConnected, CameraNetwork(network, gateway))
    }

    private fun <T> post(
        callback: (T) -> Unit,
        value: T,
    ) {
        appContext.mainExecutor.execute {
            callback(value)
        }
    }

    private companion object {
        const val TAG = "AlphaPhoto"
    }
}
