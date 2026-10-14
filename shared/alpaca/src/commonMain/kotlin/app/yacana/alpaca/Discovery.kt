// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Alpaca discovery (ASCOM Alpaca API): the client sends a UDP message, and every
 * Alpaca server that hears it answers with the port of its HTTP API.
 */
object AlpacaDiscoveryProtocol {
    const val PORT = 32227
    const val MESSAGE = "alpacadiscovery1"

    /** The IPv6 multicast group that replaces the IPv4 broadcast. */
    const val IPV6_GROUP = "ff12::a1:9aca"

    /** The port in an answer such as `{"AlpacaPort": 32323}`, or null if it is not an answer. */
    fun parseResponse(text: String): Int? {
        val json =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
        val port = json?.entries?.firstOrNull { it.key.equals("AlpacaPort", ignoreCase = true) }?.value as? JsonPrimitive
        return port?.intOrNull?.takeIf { it in 1..MAX_PORT }
    }

    private const val MAX_PORT = 65535
}

/** A server that answered discovery: the address it answered from and its Alpaca port. */
data class DiscoveryResponse(val host: String, val port: Int) {
    val isIpv6: Boolean get() = ':' in host

    /** The server root, such as `http://192.168.1.10:32323`, with an IPv6 host in brackets. */
    val url: String get() = if (isIpv6) "http://[$host]:$port" else "http://$host:$port"
}

/** Sends the discovery message and collects the answers for [discover]'s `listen` time. The platform implements it on its sockets. */
fun interface AlpacaDiscovery {
    suspend fun discover(listen: Duration): List<DiscoveryResponse>
}

/** An Alpaca server with its description and devices, found by discovery or added by address. */
data class AlpacaServer(val url: String, val description: ServerDescription, val devices: List<ConfiguredDevice>)

/** Reads the description and the devices of the server at [AlpacaClient.baseUrl]. */
suspend fun AlpacaClient.server(): AlpacaServer = AlpacaServer(baseUrl, description(), configuredDevices())

/**
 * Finds the Alpaca servers on the network, with their devices, in about [listen] plus [answer] (5 s at most).
 *
 * - A server that answers on several addresses, such as IPv4 and IPv6, appears once, with its IPv4 address.
 * - A server that answers discovery but not its HTTP API within [answer] is left out.
 */
suspend fun findServers(
    discovery: AlpacaDiscovery,
    http: HttpClient,
    listen: Duration = 2.seconds,
    answer: Duration = 2.seconds,
): List<AlpacaServer> {
    val responses = discovery.discover(listen).distinct().sortedBy { it.isIpv6 }
    val servers =
        coroutineScope {
            responses.map { response -> async { withTimeoutOrNull(answer) { serverOrNull(AlpacaClient(http, response.url)) } } }.awaitAll()
        }
    return servers.filterNotNull().distinctBy { server -> server.devices.map { it.uniqueId }.sorted().ifEmpty { listOf(server.url) } }
}

private suspend fun serverOrNull(client: AlpacaClient): AlpacaServer? = try {
    client.server()
} catch (_: IOException) {
    null
} catch (_: AlpacaProtocolException) {
    null
} catch (_: AlpacaException) {
    null
}
