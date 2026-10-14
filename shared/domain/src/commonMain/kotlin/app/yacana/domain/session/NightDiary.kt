// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

import app.yacana.domain.imaging.DiscardReason
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.HistoryFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Instant

/** The session journal's lines as they were written, oldest first, to read the night back. */
fun interface JournalLines {
    fun read(): List<String>
}

/** One thing that happened in the night, as its diary tells it, at its time. */
sealed interface DiaryEvent {
    val at: Instant

    data class Started(override val at: Instant, val name: String) : DiaryEvent

    /** The app closed, or the phone restarted, and the sequence came back. */
    data class Restored(override val at: Instant) : DiaryEvent

    data class Centred(override val at: Instant, val target: String?, val centred: Boolean, val offArcsec: Double?, val attempts: Int) :
        DiaryEvent

    /**
     * An autofocus that worked: a [trigger] fired it, or none, at the start or by hand; [change] is
     * how much the temperature changed since the one before.
     */
    data class Focused(
        override val at: Instant,
        val hfr: Double,
        val position: Int,
        val temperature: Double?,
        val trigger: String?,
        val change: Double?,
    ) : DiaryEvent

    data class FocusFailed(override val at: Instant, val reason: String?) : DiaryEvent

    data class LinkLost(override val at: Instant) : DiaryEvent

    data class LinkBack(override val at: Instant, val after: Duration, val attempts: Int) : DiaryEvent

    /** By hand without a [reason]. */
    data class Paused(override val at: Instant, val reason: String?) : DiaryEvent

    data class Resumed(override val at: Instant) : DiaryEvent

    data class SafeStopped(override val at: Instant, val reason: String, val parked: Boolean) : DiaryEvent

    /** By hand without a [reason]. */
    data class Stopped(override val at: Instant, val reason: String?) : DiaryEvent

    data class Parked(override val at: Instant) : DiaryEvent

    data class Finished(override val at: Instant) : DiaryEvent

    data class Failed(override val at: Instant, val reason: String?) : DiaryEvent

    /** [count] lights in a row discarded for the same [reason], the first [at]. */
    data class Discarded(override val at: Instant, val count: Int, val reason: DiscardReason, val target: String?, val filter: String?) :
        DiaryEvent
}

/**
 * The night's diary: from the session journal's [lines] since [since], each centring,
 * autofocus, pause, reconnection or stop with its time and reason, and from the history's
 * [frames] the discarded lights, oldest first.
 */
fun nightDiary(lines: List<String>, frames: List<HistoryFrame>, since: Instant): List<DiaryEvent> {
    val reader = DiaryReader()
    lines.mapNotNull(::entry).filter { it.at >= since }.forEach(reader::read)
    return (reader.events + discards(frames.filter { it.at >= since })).sortedBy { it.at }
}

/** A journal line: when, what and its details; null if it is not one. */
private class Entry(val at: Instant, val event: String, val details: JsonObject) {
    fun text(key: String) = (details[key] as? JsonPrimitive)?.contentOrNull

    fun number(key: String) = (details[key] as? JsonPrimitive)?.doubleOrNull

    fun whole(key: String) = (details[key] as? JsonPrimitive)?.intOrNull

    fun flag(key: String) = (details[key] as? JsonPrimitive)?.booleanOrNull
}

private fun entry(line: String): Entry? = runCatching {
    val json = Json.parseToJsonElement(line).jsonObject
    Entry(Instant.parse(json.text("t")!!), json.text("event")!!, json)
}.getOrNull()

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

/** The events that need nothing from the ones before them. */
private val SIMPLE: Map<String, (Entry) -> DiaryEvent> =
    mapOf(
        "sequence_started" to { DiaryEvent.Started(it.at, it.text("name").orEmpty()) },
        "sequence_restored" to { DiaryEvent.Restored(it.at) },
        "sequence_paused" to { DiaryEvent.Paused(it.at, it.text("reason")) },
        "sequence_resumed" to { DiaryEvent.Resumed(it.at) },
        "safe_stop" to { DiaryEvent.SafeStopped(it.at, it.text("reason").orEmpty(), it.flag("parked") == true) },
        "sequence_stopped" to { DiaryEvent.Stopped(it.at, it.text("reason")) },
        "sequence_finished" to { DiaryEvent.Finished(it.at) },
        "sequence_failed" to { DiaryEvent.Failed(it.at, it.text("reason")) },
    )

/** Reads the journal in order, keeping what each event needs from the ones before it. */
private class DiaryReader {
    val events = mutableListOf<DiaryEvent>()
    private var target: String? = null
    private var trigger: String? = null
    private var lost: Instant? = null
    private var temperature: Double? = null

    fun read(entry: Entry) {
        SIMPLE[entry.event]?.let { events += it(entry) }
        when (entry.event) {
            "sequence_item" -> item(entry)
            "centring_done" -> centred(entry)
            "sequence_autofocus_trigger" -> trigger = entry.text("triggers")
            "autofocus_done" -> focused(entry)
            "autofocus_failed" -> failed(entry)
            "link_lost" -> lost(entry)
            "link_back" -> back(entry)
        }
    }

    private fun centred(entry: Entry) {
        events +=
            DiaryEvent.Centred(entry.at, target, entry.flag("centred") == true, entry.number("offArcsec"), entry.whole("attempts") ?: 0)
    }

    private fun failed(entry: Entry) {
        events += DiaryEvent.FocusFailed(entry.at, entry.text("reason"))
        trigger = null
    }

    private fun lost(entry: Entry) {
        lost = entry.at
        events += DiaryEvent.LinkLost(entry.at)
    }

    private fun back(entry: Entry) {
        val after = lost?.let { entry.at - it } ?: Duration.ZERO
        events += DiaryEvent.LinkBack(entry.at, after, entry.whole("attempts") ?: 0)
    }

    private fun item(entry: Entry) {
        target = entry.text("target") ?: target
        if (entry.text("item") == "Park") events += DiaryEvent.Parked(entry.at)
    }

    private fun focused(entry: Entry) {
        val now = entry.number("temperature")
        val change = temperature?.let { before -> now?.let { it - before } }
        events +=
            DiaryEvent.Focused(
                entry.at,
                entry.number("hfr") ?: 0.0,
                entry.whole("position") ?: 0,
                now,
                trigger,
                change.takeIf {
                    trigger !=
                        null
                },
            )
        temperature = now ?: temperature
        trigger = null
    }
}

/** The discarded lights: a run of them in a row for the same reason, target and filter is one event. */
private fun discards(frames: List<HistoryFrame>): List<DiaryEvent.Discarded> {
    val runs = mutableListOf<DiaryEvent.Discarded>()
    var open: DiaryEvent.Discarded? = null
    frames.filter { it.type == FrameType.Light }.forEach { frame ->
        val reason = frame.discarded
        val current = open
        open =
            when {
                reason == null -> null

                current != null && current.reason == reason && current.target == frame.target && current.filter == frame.filter ->
                    current.copy(count = current.count + 1).also { runs[runs.lastIndex] = it }

                else -> DiaryEvent.Discarded(frame.at, 1, reason, frame.target, frame.filter).also { runs += it }
            }
    }
    return runs
}
