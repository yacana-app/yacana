// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.AlpacaClient
import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import app.yacana.alpaca.UdpAlpacaDiscovery
import app.yacana.alpaca.server
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.ProfilePresets
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A whole profile against OmniSim when OMNISIM_URL is set. */
class EquipmentOmniSimTest {
    private val url = System.getenv("OMNISIM_URL").orEmpty()

    @Test
    fun connectsAProfileAndShowsLiveState() {
        if (url.isBlank()) {
            println("OMNISIM_URL is not set: skipping the OmniSim integration test")
            return
        }
        HttpClient(OkHttp) { alpacaDefaults() }.use { http ->
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val profile = ProfilePresets.forServer(AlpacaClient(http, url).server())
                val site = Location(latitude = 12.34, longitude = 56.78)
                val equipment = EquipmentConnector(http, UdpAlpacaDiscovery()).connect(profile, scope, site)
                try {
                    assertEquals(emptyMap(), equipment.failures)
                    val timeAndSite = assertNotNull(equipment.timeAndSite)
                    assertTrue(abs(assertNotNull(timeAndSite.mountClockOffset).inWholeMilliseconds) <= 1_000, "$timeAndSite")
                    assertEquals(12.34, assertNotNull(timeAndSite.latitude), 1e-6)

                    // Every device shows values, and the mount's are at most 2 s old.
                    val mount = assertNotNull(equipment.mount)
                    withTimeout(2.seconds) {
                        mount.state.first { it.value != null }
                        assertNotNull(equipment.camera).state.first { it.value != null }
                        assertNotNull(equipment.focuser).state.first { it.value != null }
                        assertNotNull(equipment.filterWheel).position.first { it.updatedAt != null }
                        assertNotNull(equipment.switches).values.first { it.value != null }
                        assertNotNull(equipment.flatPanel).state.first { it.value != null }
                        assertNotNull(equipment.safetyMonitor).safe.first { it.value != null }
                    }
                    val first = assertNotNull(mount.state.value.updatedAt)
                    withTimeout(2.seconds) { mount.state.first { (it.updatedAt ?: first) > first } }
                } finally {
                    equipment.disconnect()
                    scope.cancel()
                }
            }
        }
    }
}
