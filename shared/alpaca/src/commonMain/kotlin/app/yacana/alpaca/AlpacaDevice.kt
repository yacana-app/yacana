// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The members that every ASCOM device has, and the helpers that the typed devices use.
 *
 * The typed devices use only members that exist in the current interface versions (ASCOM
 * Platform 7) and in the previous ones. Connecting sets `Connected`, which every version has:
 * the Seestar reports Platform 7 versions for some devices and older ones for others.
 */
@Suppress("TooManyFunctions") // The common ASCOM members plus one typed reader per JSON type.
abstract class AlpacaDevice(protected val client: AlpacaClient, val type: DeviceType, val number: Int) {
    suspend fun connected(): Boolean = bool("connected")

    suspend fun connect() = put("connected", "Connected" to true)

    suspend fun disconnect() = put("connected", "Connected" to false)

    suspend fun name(): String = string("name")

    suspend fun description(): String = string("description")

    suspend fun driverInfo(): String = string("driverinfo")

    suspend fun driverVersion(): String = string("driverversion")

    suspend fun interfaceVersion(): Int = int("interfaceversion")

    protected suspend fun value(member: String, vararg params: Pair<String, Any>): JsonPrimitive =
        client.get(type, number, member, params.asParameters()).jsonPrimitive

    protected suspend fun array(member: String, vararg params: Pair<String, Any>): JsonArray =
        client.get(type, number, member, params.asParameters()).jsonArray

    protected suspend fun bool(member: String, vararg params: Pair<String, Any>): Boolean = value(member, *params).boolean

    protected suspend fun int(member: String, vararg params: Pair<String, Any>): Int = value(member, *params).asInt()

    protected suspend fun double(member: String, vararg params: Pair<String, Any>): Double = value(member, *params).double

    protected suspend fun string(member: String, vararg params: Pair<String, Any>): String = value(member, *params).content

    /** Sets a property or calls a method. Parameter names are case sensitive, such as `RightAscension`. */
    protected suspend fun put(member: String, vararg params: Pair<String, Any>) {
        client.put(type, number, member, params.asParameters())
    }

    private fun Array<out Pair<String, Any>>.asParameters(): Map<String, String> = associate { (name, value) -> name to value.toString() }
}

/** Runs [block], or returns null if the device does not implement that member (error 0x400). */
suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (e: AlpacaException) {
    if (e.errorNumber == AlpacaException.NOT_IMPLEMENTED) null else throw e
}

/** Some servers send whole numbers as `600.0`. */
internal fun JsonPrimitive.asInt(): Int = intOrNull ?: double.toInt()
