// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

/**
 * The session's technical log: what the equipment and the capture did, and every error,
 * one event per line, with the time. The platform writes it to a file that the user can export.
 *
 * Writing must never fail the session: an implementation drops a line it cannot write.
 */
fun interface Journal {
    fun write(event: String, details: Map<String, Any?>)

    companion object {
        /** A journal that writes nothing, for tests and previews. */
        val None = Journal { _, _ -> }
    }
}

/** Writes [event] with [details] given as pairs. */
fun Journal.log(event: String, vararg details: Pair<String, Any?>) = write(event, details.toMap())
