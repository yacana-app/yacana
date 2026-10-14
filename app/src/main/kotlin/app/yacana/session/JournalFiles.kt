// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import app.yacana.domain.session.Journal
import app.yacana.domain.session.JournalLines
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * The session log on the phone: one file of JSON lines per day in [folder], with the
 * time and the event on each line. The last [KEEP] days are kept, for the user to export, and
 * read back for the night's diary.
 */
class JournalFiles(val folder: File) :
    Journal,
    JournalLines {
    @Synchronized
    override fun write(event: String, details: Map<String, Any?>) {
        val line = JSONObject().put("t", Instant.now().toString()).put("event", event)
        details.forEach { (name, value) -> line.put(name, json(value)) }
        try {
            folder.mkdirs()
            val file = File(folder, "yacana-${LocalDate.now()}.jsonl")
            val isNew = !file.exists()
            file.appendText(line.toString() + "\n")
            if (isNew) prune()
        } catch (_: IOException) {
            // The session never stops because of its log.
        }
    }

    /** The last two days' lines, oldest first: a night goes past midnight. */
    @Synchronized
    override fun read(): List<String> = files().takeLast(2).flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()) }

    /** The log files, newest last. */
    fun files(): List<File> = folder.listFiles { file -> file.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }

    private fun prune() = files().dropLast(KEEP).forEach { it.delete() }

    private fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), json(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(json(it)) } }
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }

    private companion object {
        const val KEEP = 14
    }
}
