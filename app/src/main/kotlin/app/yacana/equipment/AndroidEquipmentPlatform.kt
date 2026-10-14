// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.equipment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.alpaca.UdpAlpacaDiscovery
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.equipment.NoWifiException
import app.yacana.domain.profile.Location
import app.yacana.session.EquipmentNetwork
import io.ktor.client.HttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * The equipment on Android: HTTP and discovery bound to the WiFi network, which may have no
 * internet (ARCHITECTURE.md), and the phone's location from the system, without Google Play services.
 */
class AndroidEquipmentPlatform(private val context: Context) : EquipmentPlatform {
    private val network = EquipmentNetwork(context).also { it.start() }
    private var client: Pair<Network, HttpClient>? = null

    /** One client per network: a new one when the WiFi changes, as when the Seestar moves from its own WiFi to the home one. */
    @Synchronized
    override fun httpClient(): HttpClient? {
        val current = network.network.value
        val cached = client
        return when {
            current == null -> null

            cached != null && cached.first == current -> cached.second

            else -> {
                cached?.second?.close()
                network.httpClient(current).also { client = current to it }
            }
        }
    }

    override fun networkHandle(): Long? = network.network.value?.networkHandle

    /** UDP discovery on the WiFi network, with a multicast lock so that the WiFi chip passes the answers on. */
    override val discovery =
        AlpacaDiscovery { listen ->
            val current = network.network.value ?: throw NoWifiException()
            val lock = context.getSystemService(WifiManager::class.java).createMulticastLock("yacana:discovery")
            lock.setReferenceCounted(false)
            lock.acquire()
            try {
                UdpAlpacaDiscovery(bind = current::bindSocket).discover(listen)
            } finally {
                lock.release()
            }
        }

    /**
     * A fresh fix while the app is in use, or the latest known one if none comes within
     * 15 s: from the system's fused provider where there is one, which also answers indoors, else
     * from GPS or the network.
     */
    override suspend fun location(): Location? {
        val allowed = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        val enabled = providers().filter { manager.isProviderEnabled(it) }
        if (!allowed || enabled.isEmpty()) return null
        val fix = withTimeoutOrNull(FIX_TIMEOUT) { currentLocation(manager, enabled.first()) } ?: lastKnown(manager, enabled)
        return fix?.let {
            Location(latitude = it.latitude, longitude = it.longitude, elevationM = if (it.hasAltitude()) it.altitude else 0.0)
        }
    }

    private fun providers(): List<String> = listOfNotNull(
        FUSED_PROVIDER.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    )

    @SuppressLint("MissingPermission") // Checked in location().
    private suspend fun currentLocation(manager: LocationManager, provider: String): android.location.Location? =
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) { location ->
                continuation.resume(location)
            }
        }

    /** The most recent fix any of [providers] has. */
    @SuppressLint("MissingPermission") // Checked in location().
    private fun lastKnown(manager: LocationManager, providers: List<String>): android.location.Location? =
        providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val FIX_TIMEOUT = 15.seconds

        /** LocationManager.FUSED_PROVIDER, from Android 12; the app runs from Android 10. */
        const val FUSED_PROVIDER = "fused"
    }
}
