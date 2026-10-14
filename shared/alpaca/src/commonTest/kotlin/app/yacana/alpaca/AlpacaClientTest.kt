// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AlpacaClientTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): AlpacaClient {
        val engine =
            MockEngine { request ->
                requests += request
                handler(request)
            }
        return AlpacaClient(HttpClient(engine) { alpacaDefaults() }, "http://seestar.local:32323/", clientId = 42u)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf("Content-Type", "application/json"))

    @Test
    fun readsTheManagementApi() = runTest {
        val alpaca =
            client { request ->
                when (request.url.encodedPath) {
                    "/management/apiversions" -> {
                        json("""{"Value":[1],"ClientTransactionID":1,"ServerTransactionID":7}""")
                    }

                    "/management/v1/description" -> {
                        json(
                            """{"Value":{"ServerName":"Seestar","Manufacturer":"ZWO","ManufacturerVersion":"1.1.2",""" +
                                """"Location":"Field"},"ClientTransactionID":2,"ServerTransactionID":8}""",
                        )
                    }

                    else -> {
                        json(
                            """{"Value":[{"DeviceName":"Mount","DeviceType":"Telescope","DeviceNumber":0,"UniqueID":"a"},""" +
                                """{"DeviceName":"Future","DeviceType":"Spectrograph","DeviceNumber":1,"UniqueID":"b"}],""" +
                                """"ClientTransactionID":3,"ServerTransactionID":9}""",
                        )
                    }
                }
            }

        assertEquals(listOf(1), alpaca.apiVersions())
        assertEquals(ServerDescription("Seestar", "ZWO", "1.1.2", "Field"), alpaca.description())
        val devices = alpaca.configuredDevices()
        assertEquals(ConfiguredDevice("Mount", "Telescope", DeviceType.Telescope, 0, "a"), devices[0])
        assertNull(devices[1].type)
    }

    @Test
    fun getSendsClientIdsAndReturnsTheValue() = runTest {
        val alpaca =
            client { json("""{"Value":5.5,"ClientTransactionID":1,"ServerTransactionID":1,"ErrorNumber":0,"ErrorMessage":""}""") }

        val value = alpaca.get(DeviceType.Telescope, 0, "RightAscension")

        assertEquals(5.5, value.jsonPrimitive.double)
        val url = requests.single().url
        assertEquals("/api/v1/telescope/0/rightascension", url.encodedPath)
        assertEquals("42", url.parameters["ClientID"])
        assertEquals("1", url.parameters["ClientTransactionID"])
    }

    @Test
    fun putSendsAFormAndIncrementsTheTransactionId() = runTest {
        val alpaca = client { json("""{"ClientTransactionID":2,"ServerTransactionID":3,"ErrorNumber":0,"ErrorMessage":""}""") }

        alpaca.get(DeviceType.Camera, 0, "connected")
        val value = alpaca.put(DeviceType.Camera, 0, "connected", mapOf("Connected" to "true"))

        assertEquals(JsonNull, value)
        val put = requests.last()
        assertEquals(HttpMethod.Put, put.method)
        val form = (put.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("Connected=true&ClientID=42&ClientTransactionID=2", form)
    }

    @Test
    fun deviceErrorsBecomeAlpacaExceptions() = runTest {
        val alpaca =
            client { json("""{"ClientTransactionID":1,"ServerTransactionID":1,"ErrorNumber":1031,"ErrorMessage":"Not connected"}""") }

        val error = assertFailsWith<AlpacaException> { alpaca.get(DeviceType.Focuser, 0, "position") }

        assertEquals(AlpacaException.NOT_CONNECTED, error.errorNumber)
    }

    @Test
    fun httpErrorsAndBadBodiesBecomeProtocolExceptions() = runTest {
        val badRequest = client { json("Invalid value", HttpStatusCode.BadRequest) }
        assertFailsWith<AlpacaProtocolException> { badRequest.put(DeviceType.Focuser, 0, "move", mapOf("Position" to "x")) }

        val notJson = client { json("<html>router login</html>") }
        assertFailsWith<AlpacaProtocolException> { notJson.apiVersions() }
    }
}
