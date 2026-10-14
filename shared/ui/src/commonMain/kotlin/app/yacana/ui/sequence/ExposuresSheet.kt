// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.Target
import app.yacana.domain.sequence.duration
import app.yacana.domain.sequence.newTargetExposures
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.imaging.formatSeconds
import app.yacana.ui.night.hoursAndMinutes
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.exposures_count
import app.yacana.ui.resources.exposures_each_panel
import app.yacana.ui.resources.exposures_ends
import app.yacana.ui.resources.exposures_filter
import app.yacana.ui.resources.exposures_late
import app.yacana.ui.resources.exposures_remove
import app.yacana.ui.resources.exposures_time
import app.yacana.ui.resources.exposures_title
import app.yacana.ui.resources.imaging_exposure_other
import app.yacana.ui.resources.imaging_exposure_range
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Instant

/** The times the design offers with a tap, in seconds; any other is typed on the app's keypad. */
private val TIMES = listOf(10.0, 30.0, 60.0, 120.0)

/** Exposures of up to an hour, and up to this many frames of them. */
private const val MAX_SECONDS = 3600.0
private const val MAX_FRAMES = 9999

/**
 * The design "Sequence · edit exposures": the [row] of [target]'s exposures, a new one past
 * the last, for each of its panels. The filter and time are buttons; any other time and the number
 * of frames go on the app's keypad, never the system keyboard. As the number is typed it
 * says how long they take and when the target, which starts at [start], would end, against
 * [dawn]. With [onRemove], they can be taken out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongParameterList", "LongMethod") // The sheet's parts each need their part of the plan.
internal fun ExposuresSheet(
    target: Target,
    row: Int,
    filters: List<String>,
    dither: DitherOptions,
    start: Instant?,
    dawn: Instant?,
    zone: TimeZone,
    onSave: (List<Exposures>) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val comma = decimalComma()
    val first = target.exposures.getOrNull(row) ?: target.exposures.lastOrNull() ?: newTargetExposures(filters).single()
    var filter by remember { mutableStateOf(first.filter) }
    var seconds by remember { mutableStateOf(first.exposureSeconds) }
    var count by remember { mutableStateOf(first.count) }
    var typingTime by remember { mutableStateOf(false) }

    fun rows(frames: Int = count) = target.exposures.toMutableList().apply {
        val edited = first.copy(filter = filter, exposureSeconds = seconds, count = frames)
        if (row < size) set(row, edited) else add(edited)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.exposures_title, target.name),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (target.panels.size >
            1
        ) {
            Text(stringResource(Res.string.exposures_each_panel), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (filters.isNotEmpty()) {
        Label(stringResource(Res.string.exposures_filter))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { name -> FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) }) }
        }
    }
    Label(stringResource(Res.string.exposures_time))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TIMES.forEach { time ->
            FilterChip(
                selected = !typingTime && seconds == time,
                onClick = {
                    seconds = time
                    typingTime = false
                },
                label = { Text("${formatSeconds(time, comma)} s") },
            )
        }
        FilterChip(
            selected = typingTime || seconds !in TIMES,
            onClick = { typingTime = true },
            label = {
                Text(
                    if (seconds in
                        TIMES
                    ) {
                        stringResource(Res.string.imaging_exposure_other)
                    } else {
                        "${formatSeconds(seconds, comma)} s"
                    },
                )
            },
        )
    }
    if (typingTime) {
        NumberEntry(
            initial = formatSeconds(seconds, comma),
            unit = "s",
            hint = stringResource(Res.string.imaging_exposure_range, formatSeconds(1.0, comma), formatSeconds(MAX_SECONDS, comma)),
            saveLabel = stringResource(Res.string.save),
            deleteLabel = stringResource(Res.string.number_delete),
            decimal = if (comma) ',' else '.',
            valid = { it >= 1 && it <= MAX_SECONDS },
        ) {
            seconds = it
            typingTime = false
        }
        return
    }
    Label(stringResource(Res.string.exposures_count))
    var typed by remember { mutableStateOf(count) }
    val taking = target.copy(exposures = rows(typed)).duration(dither)
    val end = start?.plus(taking)
    val these = first.copy(exposureSeconds = seconds, count = typed).duration(dither) * target.panels.size.coerceAtLeast(1)
    val hint =
        listOfNotNull(
            "≈ ${shortDuration(these)}",
            end?.let {
                if (dawn != null && it > dawn) {
                    stringResource(Res.string.exposures_late, clockTime(it, zone))
                } else {
                    stringResource(Res.string.exposures_ends, clockTime(it, zone))
                }
            },
        ).joinToString("\n")
    NumberEntry(
        initial = count.toString(),
        unit = "",
        hint = hint,
        saveLabel = stringResource(Res.string.save),
        deleteLabel = stringResource(Res.string.number_delete),
        decimal = null,
        valid = { it >= 1 && it <= MAX_FRAMES },
        onSave = {
            count = it.toInt()
            onSave(rows())
        },
        onChange = { typed = it?.toInt()?.takeIf { frames -> frames in 1..MAX_FRAMES } ?: count },
    )
    onRemove?.let { remove ->
        TextButton(onClick = remove, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.exposures_remove), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** "1 h 40 min", or "12 min" under an hour. */
private fun shortDuration(duration: Duration): String =
    if (duration.inWholeMinutes < MINUTES_PER_HOUR) "${duration.inWholeMinutes} min" else hoursAndMinutes(duration)

private const val MINUTES_PER_HOUR = 60
