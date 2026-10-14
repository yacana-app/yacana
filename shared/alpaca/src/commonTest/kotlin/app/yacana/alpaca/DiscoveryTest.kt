// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveryTest {
    @Test
    fun parsesDiscoveryAnswers() {
        assertEquals(32323, AlpacaDiscoveryProtocol.parseResponse("""{"AlpacaPort": 32323}"""))
        assertEquals(11111, AlpacaDiscoveryProtocol.parseResponse("""{"alpacaport":11111}"""))
        assertNull(AlpacaDiscoveryProtocol.parseResponse(AlpacaDiscoveryProtocol.MESSAGE))
        assertNull(AlpacaDiscoveryProtocol.parseResponse("""{"AlpacaPort": 0}"""))
        assertNull(AlpacaDiscoveryProtocol.parseResponse("""{"AlpacaPort": "x"}"""))
        assertNull(AlpacaDiscoveryProtocol.parseResponse("[]"))
    }

    @Test
    fun ipv6HostsGoInBrackets() {
        assertEquals("http://192.168.0.87:32323", DiscoveryResponse("192.168.0.87", 32323).url)
        assertEquals("http://[fd00::87]:32323", DiscoveryResponse("fd00::87", 32323).url)
    }

    @Test
    fun findsEachServerOnceWithItsDevices() = runTest {
        val discovery =
            AlpacaDiscovery {
                listOf(
                    DiscoveryResponse("fd00::87", 32323),
                    DiscoveryResponse("192.168.0.87", 32323),
                    DiscoveryResponse("192.168.0.87", 32323),
                    DiscoveryResponse("192.168.0.50", 11111),
                    DiscoveryResponse("192.168.0.60", 11111),
                )
            }
        val engine =
            MockEngine.create {
                // On the test's clock, so that the server that never answers times out in virtual time.
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.url.host) {
                        // Answers discovery but its HTTP API is broken.
                        "192.168.0.50" -> respondError(HttpStatusCode.InternalServerError)

                        // Answers discovery but never answers HTTP.
                        "192.168.0.60" -> awaitCancellation()

                        else -> respond(seestarAnswer(request.url.encodedPath), headers = headersOf("Content-Type", "application/json"))
                    }
                }
            }

        val servers = findServers(discovery, HttpClient(engine) { alpacaDefaults() })

        assertEquals(listOf("http://192.168.0.87:32323"), servers.map { it.url })
        assertEquals("Seestar", servers.single().description.serverName)
        assertEquals(listOf(DeviceType.Telescope), servers.single().devices.map { it.type })
    }

    private fun seestarAnswer(path: String): String = if (path.endsWith("/description")) {
        """{"Value":{"ServerName":"Seestar","Manufacturer":"ZWO","ManufacturerVersion":"1.3.1-1","Location":""},"ErrorNumber":0}"""
    } else {
        """{"Value":[{"DeviceName":"Mount","DeviceType":"Telescope","DeviceNumber":0,"UniqueID":"mount-1"}],"ErrorNumber":0}"""
    }
}
