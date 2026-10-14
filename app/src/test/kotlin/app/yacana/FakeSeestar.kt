// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf

/**
 * A Seestar S50 Pro in memory, with the devices and values that spike S3 read from the real one,
 * pointing at M 31. A PUT with one parameter is stored, so properties read back what was set.
 */
class FakeSeestar {
    /** Whether the camera holds its frames: an exposure never gets ready, as a long one. */
    @Volatile var holdFrames = false

    private val values =
        mutableMapOf(
            "telescope/0/alignmentmode" to "1",
            "telescope/0/equatorialsystem" to "1",
            "telescope/0/canfindhome" to "true",
            "telescope/0/canpark" to "true",
            "telescope/0/canunpark" to "true",
            "telescope/0/cansetpark" to "false",
            "telescope/0/canpulseguide" to "true",
            "telescope/0/cansettracking" to "true",
            "telescope/0/canslewasync" to "true",
            "telescope/0/cansync" to "true",
            "telescope/0/canmoveaxis" to "true",
            "telescope/0/trackingrates" to "[0,1,2]",
            "telescope/0/axisrates" to """[{"Minimum":0,"Maximum":6}]""",
            "telescope/0/rightascension" to "0.7123",
            "telescope/0/declination" to "41.269",
            "telescope/0/altitude" to "52.17",
            "telescope/0/azimuth" to "78.53",
            "telescope/0/siderealtime" to "1.5",
            "telescope/0/slewing" to "false",
            "telescope/0/tracking" to "true",
            "telescope/0/trackingrate" to "0",
            "telescope/0/atpark" to "false",
            "telescope/0/athome" to "false",
            "telescope/0/sitelatitude" to "40.0",
            "telescope/0/sitelongitude" to "-3.0",
            "telescope/0/utcdate" to "\"2026-10-04T22:00:00Z\"",
            "camera/0/sensortype" to "2",
            "camera/0/sensorname" to "\"\"",
            "camera/0/cameraxsize" to "2160",
            "camera/0/cameraysize" to "3840",
            "camera/0/pixelsizex" to "2.9",
            "camera/0/pixelsizey" to "2.9",
            "camera/0/bayeroffsetx" to "1",
            "camera/0/bayeroffsety" to "0",
            "camera/0/maxbinx" to "1",
            "camera/0/maxbiny" to "1",
            "camera/0/canasymmetricbin" to "false",
            "camera/0/gains" to "[]",
            "camera/0/gainmin" to "0",
            "camera/0/gainmax" to "600",
            "camera/0/offsetmin" to "0",
            "camera/0/offsetmax" to "0",
            "camera/0/exposuremin" to "3e-05",
            "camera/0/exposuremax" to "2000",
            "camera/0/canabortexposure" to "true",
            "camera/0/canstopexposure" to "true",
            "camera/0/cansetccdtemperature" to "false",
            "camera/0/ccdtemperature" to "24.6875",
            "camera/0/cangetcoolerpower" to "false",
            "camera/0/maxadu" to "65535",
            "camera/0/readoutmodes" to """["0"]""",
            "camera/0/camerastate" to "0",
            "camera/0/imageready" to "false",
            "focuser/0/absolute" to "true",
            "focuser/0/maxstep" to "2600",
            "focuser/0/maxincrement" to "2600",
            "focuser/0/tempcompavailable" to "false",
            "focuser/0/temperature" to "12.4",
            "focuser/0/position" to "1520",
            "focuser/0/ismoving" to "false",
            "filterwheel/0/names" to """["Dark","IR","LP"]""",
            "filterwheel/0/focusoffsets" to "[0,0,0]",
            "filterwheel/0/position" to "1",
            "switch/0/maxswitch" to "1",
            "switch/0/getswitchname" to "\"Dew heater\"",
            "switch/0/getswitchdescription" to "\"Dew heater\"",
            "switch/0/canwrite" to "true",
            "switch/0/minswitchvalue" to "0",
            "switch/0/maxswitchvalue" to "1",
            "switch/0/switchstep" to "1",
            "switch/0/getswitchvalue" to "1",
        )

    /** Makes [member] answer [value], as JSON, as a test needs it: the mount parked, or pointing elsewhere. */
    fun set(member: String, value: String) {
        synchronized(puts) { values[member] = value }
    }

    /** Every PUT it was sent, in order: the member and its parameters. */
    val puts = mutableListOf<Pair<String, Map<String, String>>>()

    private val devices =
        listOf(
            Triple("Telescope", 0, "mount-1"),
            Triple("Camera", 0, "camera-0"),
            Triple("Camera", 1, "camera-1"),
            Triple("Focuser", 0, "focuser-0"),
            Triple("Focuser", 1, "focuser-1"),
            Triple("FilterWheel", 0, "wheel-0"),
            Triple("Switch", 0, "switch-0"),
        )

    val http: HttpClient =
        HttpClient(
            MockEngine { request ->
                val form =
                    (request.body as? OutgoingContent.ByteArrayContent)
                        ?.bytes()
                        ?.decodeToString()
                        ?.split('&')
                        ?.map { it.substringBefore('=') to it.substringAfter('=') }
                        ?.filter { it.first != "ClientID" && it.first != "ClientTransactionID" }
                        .orEmpty()
                respond(answer(request.method, request.url.encodedPath, form), headers = headersOf("Content-Type", "application/json"))
            },
        ) { alpacaDefaults() }

    private fun answer(method: HttpMethod, path: String, form: List<Pair<String, String>>): String {
        val member = path.removePrefix("/api/v1/")
        return when {
            path.endsWith("/configureddevices") -> ok(
                devices.joinToString(",", "[", "]") { (type, number, id) ->
                    val name = "Seestar S50 Pro_0000 " + if (type == "Telescope") type else "$type $number"
                    """{"DeviceName":"$name","DeviceType":"$type","DeviceNumber":$number,"UniqueID":"$id"}"""
                },
            )

            path.endsWith("/apiversions") -> ok("[1]")

            path.endsWith(
                "/description",
            ) -> ok("""{"ServerName":"ASCOM Alpaca","Manufacturer":"ASCOM Initiative","ManufacturerVersion":"1.3.1-1","Location":""}""")

            method == HttpMethod.Put -> {
                synchronized(puts) { puts += member to form.toMap() }
                // An exposure is ready as soon as it starts, unless the frames are held.
                if (member == "camera/0/startexposure" && !holdFrames) values["camera/0/imageready"] = "true"
                if (form.size == 1) values[member] = literal(form.single().second)
                ok(null)
            }

            member in values -> ok(values.getValue(member))

            else -> """{"ErrorNumber":1024,"ErrorMessage":"Not implemented"}"""
        }
    }

    private fun literal(text: String) = if (text.toDoubleOrNull() != null || text == "true" || text == "false") text else "\"$text\""

    private fun ok(value: String?) = if (value == null) """{"ErrorNumber":0}""" else """{"Value":$value,"ErrorNumber":0}"""
}
