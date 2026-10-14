// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * File names from a pattern with N.I.N.A.'s tokens, such as `$$TARGETNAME$$`. A `/` in
 * the pattern makes folders, so one pattern gives each night and target its own folder.
 */
object FileNames {
    /**
     * The default: a folder per night (the local date 12 hours earlier, so a night after midnight
     * keeps its evening's date), then one per target and frame type.
     */
    const val DEFAULT_PATTERN =
        "\$\$DATEMINUS12\$\$/\$\$TARGETNAME\$\$/\$\$IMAGETYPE\$\$/" +
            "\$\$DATETIME\$\$_\$\$FILTER\$\$_\$\$EXPOSURETIME\$\$s_\$\$FRAMENR\$\$"

    /** The tokens this app knows, as N.I.N.A. names them. */
    val TOKENS =
        listOf(
            "DATEMINUS12",
            "DATE",
            "DATETIME",
            "TIME",
            "TARGETNAME",
            "IMAGETYPE",
            "FILTER",
            "EXPOSURETIME",
            "FRAMENR",
            "GAIN",
            "OFFSET",
            "BINNING",
            "SENSORTEMP",
        )

    /**
     * The relative path of a frame for [pattern], without extension. Each token becomes a value from
     * [capture] in the [zone]'s local time, and characters that file systems refuse become `_`.
     *
     * @param frameNumber counts the frames of the session from 1; written with 4 digits.
     */
    fun render(pattern: String, capture: Capture, frameNumber: Int, zone: TimeZone): String =
        render(pattern, capture, capture.type.fits, frameNumber.toString().padStart(FRAME_DIGITS, '0'), zone)

    /**
     * The relative path of the live stack whose first frame was [capture]: [pattern] with
     * `STACK` as its type and its number, so that it never takes a frame's name, and the same for
     * the whole stack, which is saved over as it grows.
     */
    fun renderStack(pattern: String, capture: Capture, zone: TimeZone): String {
        val path = render(pattern, capture, STACK, STACK, zone)
        return if (STACK in path) path else "${path}_$STACK"
    }

    private fun render(pattern: String, capture: Capture, imageType: String, frameNumber: String, zone: TimeZone): String {
        val local = capture.start.toLocalDateTime(zone)
        val night = (capture.start - NIGHT_SHIFT).toLocalDateTime(zone)
        val values =
            mapOf(
                "DATEMINUS12" to date(night),
                "DATE" to date(local),
                "DATETIME" to "${date(local)}_${time(local)}",
                "TIME" to time(local),
                "TARGETNAME" to (capture.target?.ifBlank { null } ?: NO_TARGET),
                "IMAGETYPE" to imageType,
                "FILTER" to (capture.filter?.ifBlank { null } ?: NO_FILTER),
                "EXPOSURETIME" to exposure(capture.exposureSeconds),
                "FRAMENR" to frameNumber,
                "GAIN" to (capture.gain?.toString() ?: ""),
                "OFFSET" to (capture.offset?.toString() ?: ""),
                "BINNING" to "${capture.binX}x${capture.binY}",
                "SENSORTEMP" to (capture.sensorTemperature?.let { (kotlin.math.round(it * 10) / 10).toString() } ?: ""),
            )
        return substitute(pattern, values)
    }

    /**
     * What [pattern] gives for a sample frame taken at [start]: a 20 s light of M 31 with the LP
     * filter at gain 80, the first of the night. Settings shows it so the pattern can be checked.
     */
    fun example(pattern: String, start: Instant, zone: TimeZone): String {
        val local = start.toLocalDateTime(zone)
        val night = (start - NIGHT_SHIFT).toLocalDateTime(zone)
        val values =
            mapOf(
                "DATEMINUS12" to date(night),
                "DATE" to date(local),
                "DATETIME" to "${date(local)}_${time(local)}",
                "TIME" to time(local),
                "TARGETNAME" to "M 31",
                "IMAGETYPE" to FrameType.Light.fits,
                "FILTER" to "LP",
                "EXPOSURETIME" to exposure(EXAMPLE_SECONDS),
                "FRAMENR" to "1".padStart(FRAME_DIGITS, '0'),
                "GAIN" to "80",
                "OFFSET" to "",
                "BINNING" to "1x1",
                "SENSORTEMP" to "",
            )
        return substitute(pattern, values)
    }

    private fun substitute(pattern: String, values: Map<String, String>): String {
        val rendered = TOKEN.replace(pattern) { match -> values[match.groupValues[1]] ?: match.value }
        return rendered
            .replace('\\', '/')
            .split('/')
            .map { part -> part.map { if (it in FORBIDDEN || it.code < SPACE) '_' else it }.joinToString("").trim().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private fun date(time: LocalDateTime) = "${time.year}-${two(time.month.ordinal + 1)}-${two(time.day)}"

    private fun time(time: LocalDateTime) = "${two(time.hour)}-${two(time.minute)}-${two(time.second)}"

    private fun two(value: Int) = value.toString().padStart(2, '0')

    /** `20.00` as N.I.N.A. writes exposure times, with a point whatever the language. */
    private fun exposure(seconds: Double): String {
        val hundredths = kotlin.math.round(seconds * HUNDRED).toLong()
        return "${hundredths / HUNDRED.toLong()}.${two((hundredths % HUNDRED.toLong()).toInt())}"
    }

    private const val EXAMPLE_SECONDS = 20.0
    private val TOKEN = Regex("""\$\$([A-Z0-9]+)\$\$""")
    private val NIGHT_SHIFT = 12.hours
    private const val FORBIDDEN = "<>:\"|?*"
    private const val SPACE = 32
    private const val FRAME_DIGITS = 4
    private const val HUNDRED = 100.0
    private const val NO_TARGET = "Snapshot"
    private const val NO_FILTER = "NoFilter"
    private const val STACK = "STACK"
}
