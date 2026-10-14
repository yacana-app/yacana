// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * An append-only log with one JSON object per line, written to a file that a PC can
 * copy over USB (Android/data/app.yacana/files/). Each line has the time and an event name.
 *
 * A line that cannot be written is dropped: the session must never stop because of its log.
 */
class SessionLog(val file: File) {
    @Synchronized
    fun write(event: String, vararg fields: Pair<String, Any?>) {
        val line = JSONObject().put("t", Instant.now().toString()).put("event", event)
        fields.forEach { (name, value) -> line.put(name, value ?: JSONObject.NULL) }
        file.appendText(line.toString() + "\n")
    }
}
