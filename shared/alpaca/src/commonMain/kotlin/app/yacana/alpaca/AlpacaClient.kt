// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.random.nextUInt

/**
 * Talks to one Alpaca server over HTTP.
 *
 * One server can host several devices. This class covers the management API
 * and generic device calls; typed device classes build on [get] and [put].
 *
 * @param http a client whose engine the platform chose. On Android its sockets
 *   are bound to the equipment's WiFi network (ARCHITECTURE.md).
 * @param baseUrl the server root, such as `http://192.168.1.10:32323`.
 */
@OptIn(ExperimentalAtomicApi::class)
class AlpacaClient(private val http: HttpClient, baseUrl: String, private val clientId: UInt = Random.nextUInt(1u..UInt.MAX_VALUE)) {
    /** The server root, without a trailing slash. */
    val baseUrl: String = baseUrl.trimEnd('/')
    private val transactionId = AtomicInt(0)

    /** Alpaca API versions the server supports, such as `[1]`. */
    suspend fun apiVersions(): List<Int> = call(get("$baseUrl/management/apiversions")).jsonArray.map { it.jsonPrimitive.int }

    /** The server's name, manufacturer and location. */
    suspend fun description(): ServerDescription {
        val value = call(get("$baseUrl/management/v1/description")).jsonObject
        return ServerDescription(
            serverName = value.string("ServerName"),
            manufacturer = value.string("Manufacturer"),
            manufacturerVersion = value.string("ManufacturerVersion"),
            location = value.string("Location"),
        )
    }

    /** The devices the server hosts. */
    suspend fun configuredDevices(): List<ConfiguredDevice> =
        call(get("$baseUrl/management/v1/configureddevices")).jsonArray.map { element ->
            val device = element.jsonObject
            val typeName = device.string("DeviceType")
            ConfiguredDevice(
                name = device.string("DeviceName"),
                typeName = typeName,
                type = DeviceType.fromName(typeName),
                number = device.getValue("DeviceNumber").jsonPrimitive.int,
                uniqueId = device.string("UniqueID"),
            )
        }

    /**
     * Reads a property, such as `rightascension`, and returns its `Value`.
     *
     * @throws AlpacaException when the device reports an error.
     * @throws AlpacaProtocolException when the server answers with an HTTP error or bad JSON.
     */
    suspend fun get(type: DeviceType, number: Int, member: String, params: Map<String, String> = emptyMap()): JsonElement =
        call(get(deviceUrl(type, number, member), params))

    /**
     * Sets a property or calls a method, such as `connected` with `Connected=true`.
     * Parameter names are case sensitive. Returns the `Value`, or [JsonNull] if there is none.
     */
    suspend fun put(type: DeviceType, number: Int, member: String, params: Map<String, String> = emptyMap()): JsonElement {
        val response =
            http.submitForm(
                url = deviceUrl(type, number, member),
                formParameters =
                parameters {
                    params.forEach { (name, value) -> append(name, value) }
                    append("ClientID", clientId.toString())
                    append("ClientTransactionID", nextTransactionId())
                },
            ) { method = HttpMethod.Put }
        return call(response)
    }

    private suspend fun get(url: String, params: Map<String, String> = emptyMap()): HttpResponse = http.get(url) {
        params.forEach { (name, value) -> parameter(name, value) }
        parameter("ClientID", clientId.toString())
        parameter("ClientTransactionID", nextTransactionId())
    }

    private fun deviceUrl(type: DeviceType, number: Int, member: String) = "$baseUrl/api/v1/${type.path}/$number/${member.lowercase()}"

    private fun nextTransactionId(): String = transactionId.incrementAndFetch().toUInt().toString()

    private suspend fun call(response: HttpResponse): JsonElement {
        val envelope = envelope(response)
        val errorNumber = envelope["ErrorNumber"]?.jsonPrimitive?.int ?: 0
        if (errorNumber != 0) {
            throw AlpacaException(errorNumber, envelope["ErrorMessage"]?.jsonPrimitive?.content.orEmpty())
        }
        return envelope["Value"] ?: JsonNull
    }

    /** The JSON object of a successful response. */
    private suspend fun envelope(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AlpacaProtocolException("HTTP ${response.status.value} from ${response.call.request.url}: $text")
        }
        return try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            // Also catches SerializationException, a subclass.
            throw AlpacaProtocolException("Not an Alpaca response from ${response.call.request.url}: $text", e)
        }
    }

    private fun JsonObject.string(name: String): String = get(name)?.jsonPrimitive?.content.orEmpty()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 120_000L

        /** Timeouts for a local network: fail fast on connect, allow long image downloads. */
        fun HttpClientConfig<*>.alpacaDefaults() {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }
}
