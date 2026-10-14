// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * The WiFi network of the equipment, which usually has no internet (ARCHITECTURE.md).
 *
 * Android sends traffic to mobile data when the WiFi has no internet, unless the
 * sockets are bound to the WiFi network. This class asks for that network and makes
 * HTTP clients whose sockets and name lookups go through it.
 */
class EquipmentNetwork(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val current = MutableStateFlow<Network?>(null)

    /** The WiFi network while it is up, or null. */
    val network: StateFlow<Network?> = current.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current.value = network
            }

            override fun onLost(network: Network) {
                if (current.value == network) current.value = null
            }
        }

    fun start() {
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        connectivity.requestNetwork(request, callback)
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        current.value = null
    }

    /** An HTTP client for Alpaca whose traffic goes only through [network]. */
    fun httpClient(network: Network): HttpClient = HttpClient(OkHttp) {
        alpacaDefaults()
        engine {
            preconfigured =
                OkHttpClient
                    .Builder()
                    .socketFactory(network.socketFactory)
                    .dns { host -> network.getAllByName(host).toList() }
                    .build()
        }
    }
}
