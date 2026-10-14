// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs against the ASCOM Alpaca simulators (OmniSim) when OMNISIM_URL is set,
 * for example `http://localhost:32323`. CI starts OmniSim on Linux.
 */
class OmniSimIntegrationTest {
    private val url = System.getenv("OMNISIM_URL").orEmpty()

    private fun withOmniSim(block: suspend (AlpacaClient) -> Unit) {
        if (url.isBlank()) {
            println("OMNISIM_URL is not set: skipping the OmniSim integration test")
            return
        }
        HttpClient(OkHttp) { alpacaDefaults() }.use { http ->
            runBlocking { block(AlpacaClient(http, url)) }
        }
    }

    @Test
    fun listsTheSimulatedDevices() = withOmniSim { alpaca ->
        assertContains(alpaca.apiVersions(), 1)
        val types = alpaca.configuredDevices().mapNotNull { it.type }.toSet()
        assertTrue(types.containsAll(DeviceType.entries), "OmniSim should simulate every device type, got $types")
    }

    @Test
    fun connectsToTheTelescopeAndReadsItsPosition() = withOmniSim { alpaca ->
        alpaca.put(DeviceType.Telescope, 0, "connected", mapOf("Connected" to "true"))
        try {
            assertTrue(alpaca.get(DeviceType.Telescope, 0, "connected").jsonPrimitive.boolean)
            val ra = alpaca.get(DeviceType.Telescope, 0, "rightascension").jsonPrimitive.double
            assertTrue(ra in 0.0..24.0, "right ascension $ra h is out of range")
        } finally {
            alpaca.put(DeviceType.Telescope, 0, "connected", mapOf("Connected" to "false"))
        }
    }

    @Test
    fun findsOmniSimByDiscovery() = withOmniSim { alpaca ->
        HttpClient(OkHttp) { alpacaDefaults() }.use { http ->
            val start = System.nanoTime()
            val servers = findServers(UdpAlpacaDiscovery(), http)
            val seconds = (System.nanoTime() - start) / 1e9
            val omniSim = servers.single { server -> server.devices.any { it.name == "Alpaca Telescope Simulator" } }
            assertEquals(alpaca.configuredDevices(), omniSim.devices)
            // Devices appear within 5 s.
            assertTrue(seconds < 5.seconds.inWholeSeconds, "discovery took $seconds s")
        }
    }
}
