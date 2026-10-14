// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.yacana.domain.session.DiaryEvent
import app.yacana.domain.session.NightSummary
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.months
import app.yacana.ui.resources.summary_and_parked
import app.yacana.ui.resources.summary_by_target
import app.yacana.ui.resources.summary_close
import app.yacana.ui.resources.summary_diary
import app.yacana.ui.resources.summary_discarded
import app.yacana.ui.resources.summary_export
import app.yacana.ui.resources.summary_failed
import app.yacana.ui.resources.summary_finished
import app.yacana.ui.resources.summary_frames
import app.yacana.ui.resources.summary_frames_value
import app.yacana.ui.resources.summary_hfr
import app.yacana.ui.resources.summary_hfr_value
import app.yacana.ui.resources.summary_incidents
import app.yacana.ui.resources.summary_integration
import app.yacana.ui.resources.summary_night
import app.yacana.ui.resources.summary_safe
import app.yacana.ui.resources.summary_stopped
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * "Night · summary": the night that ended and how, its numbers, the lights kept by target
 * and what went off plan. The ✕ goes back to preparing the night.
 */
@Composable
fun NightSummaryCards(summary: NightSummary, diary: List<DiaryEvent>, evening: LocalDate, zone: TimeZone, onClose: () -> Unit) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(nightName(evening), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(Touch.Min)) {
                Icon(
                    YacanaIcons.Close,
                    contentDescription = stringResource(Res.string.summary_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ended(summary, diary, zone)?.let { Muted(it) }
    }
    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val comma = decimalComma()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(stringResource(Res.string.summary_integration), span(summary.integration), Modifier.weight(1f))
            Metric(stringResource(Res.string.summary_frames), summary.frames.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(stringResource(Res.string.summary_discarded), summary.discarded.toString(), Modifier.weight(1f))
            Metric(
                stringResource(Res.string.summary_hfr),
                summary.medianHfr?.let { stringResource(Res.string.summary_hfr_value, formatDecimal(it, 1, comma)) } ?: "—",
                Modifier.weight(1f),
            )
        }
    }
    if (summary.byTarget.isNotEmpty()) {
        SectionCard {
            Muted(stringResource(Res.string.summary_by_target))
            summary.byTarget.forEach { kept ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(listOfNotNull(kept.target.ifEmpty { null }, kept.filter).joinToString(" · "), modifier = Modifier.weight(1f))
                    val seconds = kept.exposureSeconds
                    val exposure = if (seconds ==
                        seconds.roundToInt().toDouble()
                    ) {
                        seconds.roundToInt().toString()
                    } else {
                        formatDecimal(seconds, 1, decimalComma())
                    }
                    Muted(stringResource(Res.string.summary_frames_value, kept.count, exposure))
                }
            }
        }
    }
    val incidents = summary.incidents.mapNotNull { event -> event.line()?.let { event to it } }
    if (incidents.isNotEmpty()) {
        SectionCard {
            Muted(stringResource(Res.string.summary_incidents))
            incidents.forEach { (event, line) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        line.icon,
                        contentDescription = null,
                        tint = line.tint ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(line.heading, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Muted(listOfNotNull(clockTime(event.at, zone), line.short).joinToString(" · "))
                }
            }
        }
    }
}

/** The summary's buttons: "View diary" and "Export". */
@Composable
fun SummaryButtons(onDiary: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceButton(stringResource(Res.string.summary_diary), onDiary, Modifier.weight(1f))
        AccentButton(onClick = onExport, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
            Icon(YacanaIcons.FileExport, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.summary_export), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** "Night of October 2". */
@Composable
internal fun nightName(evening: LocalDate): String =
    stringResource(Res.string.summary_night, evening.day, stringArrayResource(Res.array.months)[evening.month.ordinal])

/** "Finished at 04:50 · mount parked", or how else it ended. */
@Composable
private fun ended(summary: NightSummary, diary: List<DiaryEvent>, zone: TimeZone): String? {
    val at = summary.ended ?: return null
    val how =
        when (diary.lastOrNull { it.at == at }) {
            is DiaryEvent.Stopped -> Res.string.summary_stopped
            is DiaryEvent.SafeStopped -> Res.string.summary_safe
            is DiaryEvent.Failed -> Res.string.summary_failed
            else -> Res.string.summary_finished
        }
    val text = stringResource(how, clockTime(at, zone))
    return if (summary.parked) stringResource(Res.string.summary_and_parked, text) else text
}

/** One of the design's four numbers: its name over its value. */
@Composable
private fun Metric(name: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(
            RoundedCornerShape(12.dp),
        ).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Muted(name)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
