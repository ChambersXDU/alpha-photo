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
        if (networkCallback != null) {
            onError("Camera Wi-Fi connection is already in progress.")
            return
        }

        if (!wifiManager.isWifiEnabled) {
            onError("Phone Wi-Fi is off.")
            return
        }

        val request = try {
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setWpa2Passphrase(credentials.password)

            credentials.ssid?.let(specifierBuilder::setSsid)
            credentials.bssid?.let { bssid ->
                specifierBuilder.setBssid(MacAddress.fromString(bssid))
            }

            val specifier = specifierBuilder.build()

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        } catch (error: Throwable) {
            onError(
                "Camera Wi-Fi request is invalid: " +
                    (error.message ?: error.javaClass.simpleName),
            )
            return
        }

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
                if (networkCallback === this) {
                    networkCallback = null
                    deliveredNetwork = null
                }
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
        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (error: Throwable) {
            networkCallback = null
            deliveredNetwork = null
            val message =
                "Android could not start the camera Wi-Fi request: " +
                    (error.message ?: error.javaClass.simpleName)
            Log.e(TAG, message, error)
            post(onError, message)
        }
    }

    fun release() {
        val callback = networkCallback ?: return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Camera Wi-Fi callback was already released.", error)
        } finally {
            networkCallback = null
            deliveredNetwork = null
        }
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
