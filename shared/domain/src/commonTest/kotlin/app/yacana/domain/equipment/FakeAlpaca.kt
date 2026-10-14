// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher

/**
 * An Alpaca server in memory for domain tests (ARCHITECTURE.md).
 *
 * - A GET returns `values["telescope/0/rightascension"]`, a JSON literal, or error 0x400 if
 *   the member is in [notImplemented] or has no value.
 * - A PUT is recorded in [puts]. A PUT with one parameter also stores it as the member's value,
 *   so properties read back what was set; [onPut] can act on methods.
 */
class FakeAlpaca(val host: String = "fake") {
    val url = "http://$host:32323"
    val values = mutableMapOf<String, String>()
    val notImplemented = mutableSetOf<String>()
    val puts = mutableListOf<Pair<String, Map<String, String>>>()
    var onPut: (member: String, form: Map<String, String>) -> Unit = { _, _ -> }

    /** While true the server does not answer, as when the WiFi is gone. */
    var down = false

    /** Devices for the management API: (type path, number, unique ID). */
    val devices = mutableListOf<Triple<String, Int, String>>()

    fun respond(method: HttpMethod, path: String, form: Map<String, String>): String {
        val member = path.removePrefix("/api/v1/")
        return when {
            path == "/management/v1/configureddevices" -> ok(
                devices.joinToString(",", "[", "]") { (type, number, id) ->
                    """{"DeviceName":"$type $number","DeviceType":"$type","DeviceNumber":$number,"UniqueID":"$id"}"""
                },
            )

            path == "/management/v1/description" -> ok(
                """{"ServerName":"Fake","Manufacturer":"Yacana tests","ManufacturerVersion":"1","Location":""}""",
            )

            method == HttpMethod.Put -> {
                puts += member to form
                if (form.size == 1) values[member] = literal(form.values.single())
                onPut(member, form)
                ok(null)
            }

            member in notImplemented || member !in values -> """{"ErrorNumber":1024,"ErrorMessage":"$member is not implemented"}"""

            else -> ok(values.getValue(member))
        }
    }

    private fun ok(value: String?) = if (value == null) """{"ErrorNumber":0}""" else """{"Value":$value,"ErrorNumber":0}"""

    private fun literal(text: String) = if (text == "true" || text == "false" || text.toDoubleOrNull() != null) text else "\"$text\""
}

/**
 * An HTTP client that reaches [servers] by host; any other host is unavailable. Pass the test's
 * [dispatcher] so that the requests run in virtual time, like the timeouts around them.
 */
fun fakeHttp(dispatcher: CoroutineDispatcher, vararg servers: FakeAlpaca): HttpClient = HttpClient(
    MockEngine.create {
        this.dispatcher = dispatcher
        addHandler { request ->
            val server =
                servers.firstOrNull { it.host == request.url.host && !it.down }
                    ?: return@addHandler respondError(HttpStatusCode.ServiceUnavailable)
            val form =
                (request.body as? OutgoingContent.ByteArrayContent)
                    ?.bytes()
                    ?.decodeToString()
                    ?.split('&')
                    ?.map { it.substringBefore('=') to it.substringAfter('=').decodeURLQueryComponent(plusIsSpace = true) }
                    ?.filter { it.first != "ClientID" && it.first != "ClientTransactionID" }
                    ?.toMap()
                    .orEmpty()
            respond(server.respond(request.method, request.url.encodedPath, form), headers = headersOf("Content-Type", "application/json"))
        }
    },
) { alpacaDefaults() }

/** The Seestar's mount as spike S3 found it, pointing near the zenith of latitude 40° at sidereal time 2 h. */
fun FakeAlpaca.seestarMount() {
    devices += Triple("Telescope", 0, "mount-1")
    val t = "telescope/0/"
    mapOf(
        "connected" to "false",
        "alignmentmode" to "1",
        "equatorialsystem" to "1",
        "canfindhome" to "true",
        "canpark" to "true",
        "canunpark" to "true",
        "cansetpark" to "false",
        "canpulseguide" to "true",
        "cansettracking" to "true",
        "canslewasync" to "true",
        "cansync" to "true",
        "canmoveaxis" to "true",
        "trackingrates" to "[0,1,2]",
        "rightascension" to "2.0",
        "declination" to "39.0",
        "altitude" to "89.0",
        "azimuth" to "180.0",
        "siderealtime" to "2.0",
        "slewing" to "false",
        "tracking" to "false",
        "trackingrate" to "0",
        "atpark" to "false",
        "athome" to "true",
        "sitelatitude" to "40.0",
        "sitelongitude" to "-3.0",
        "utcdate" to "\"2026-10-04T21:00:00Z\"",
    ).forEach { (member, value) -> values[t + member] = value }
    notImplemented += t + "siteelevation"
}
