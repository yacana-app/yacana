// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.profile

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A profile as a file (ARCHITECTURE.md): JSON with `"kind": "profile"` and `"schemaVersion"` at the top.
 *
 * - Files from older versions go through [migrations] in order.
 * - Files from newer versions still open: unknown keys are ignored, so only the new fields are lost.
 * - Default values are written, so files stay explicit.
 */
object ProfileFile {
    const val KIND = "profile"
    const val SCHEMA_VERSION = 1

    /** The migration at index N - 1 turns version N into N + 1. Each has a test with a sample of the old version. */
    private val migrations: List<(JsonObject) -> JsonObject> = emptyList()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

    fun encode(profile: Profile): String {
        val fields = json.encodeToJsonElement(Profile.serializer(), profile).jsonObject
        val file =
            buildJsonObject {
                put("kind", JsonPrimitive(KIND))
                put("schemaVersion", JsonPrimitive(SCHEMA_VERSION))
                fields.forEach { (key, value) -> put(key, value) }
            }
        return json.encodeToString(JsonObject.serializer(), file) + "\n"
    }

    /** Reads a profile file. [Decoded.migrated] says that it was older and should be saved again. */
    fun decode(text: String): Decoded {
        val file = parse(text)
        val version = schemaVersion(file)
        var fields = file
        for (from in version until SCHEMA_VERSION) fields = migrations[from - 1](fields)
        return Decoded(toProfile(JsonObject(fields - "kind" - "schemaVersion")), migrated = version < SCHEMA_VERSION)
    }

    private fun parse(text: String): JsonObject {
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw ProfileFormatException("Not JSON", e)
            }
        return element as? JsonObject ?: throw ProfileFormatException("Not a JSON object")
    }

    private fun schemaVersion(file: JsonObject): Int {
        if ((file["kind"] as? JsonPrimitive)?.content != KIND) throw ProfileFormatException("Not a profile")
        return (file["schemaVersion"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 } ?: throw ProfileFormatException("No schema version")
    }

    private fun toProfile(fields: JsonObject): Profile = try {
        json.decodeFromJsonElement(Profile.serializer(), fields)
    } catch (e: SerializationException) {
        throw ProfileFormatException("Invalid profile: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw ProfileFormatException("Invalid profile: ${e.message}", e)
    }

    data class Decoded(val profile: Profile, val migrated: Boolean)
}

/** A file that is not a profile Yacana can read. */
class ProfileFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
