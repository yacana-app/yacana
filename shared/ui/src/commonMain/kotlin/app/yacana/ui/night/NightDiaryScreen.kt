// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.HistoryFrame
import app.yacana.domain.session.DiaryEvent
import app.yacana.domain.session.JournalLines
import app.yacana.domain.session.nightDiary
import app.yacana.ui.AppServices
import app.yacana.ui.design.SectionCard
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.diary_empty
import app.yacana.ui.resources.diary_events
import app.yacana.ui.sky.evening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * "Night · diary": what happened tonight, each centring, autofocus, pause, reconnection or
 * stop with its time and reason, and the discarded lights; read back from the session journal.
 */
@Composable
fun NightDiaryScreen(services: AppServices) {
    val frames by services.capture.history.frames.collectAsState()
    val zone = services.zone()
    val evening = evening(services.clock.now(), zone)
    val diary = rememberDiary(services.journal, frames, nightStart(evening, zone))
    val lines = diary.mapNotNull { event -> event.line()?.let { event to it } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Text(nightName(evening), style = MaterialTheme.typography.titleMedium)
            val first = lines.firstOrNull()?.first?.at
            val last = lines.lastOrNull()?.first?.at
            Text(
                if (first == null || last == null) {
                    stringResource(Res.string.diary_empty)
                } else {
                    "${clockTime(
                        first,
                        zone,
                    )} – ${clockTime(last, zone)} · ${pluralStringResource(Res.plurals.diary_events, lines.size, lines.size)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lines.isNotEmpty()) {
            SectionCard {
                lines.forEachIndexed { index, (event, line) -> DiaryRow(event, line, zone, last = index == lines.lastIndex) }
            }
        }
    }
}

/**
 * The night's diary since [since], read from [journal] off the main thread; again as the history
 * grows, and when [moment] changes, as the run's status when it ends after its last frame.
 */
@Composable
internal fun rememberDiary(journal: JournalLines, frames: List<HistoryFrame>, since: Instant, moment: Any? = null): List<DiaryEvent> {
    val diary by produceState(emptyList<DiaryEvent>(), journal, frames, since, moment) {
        value = withContext(Dispatchers.Default) { nightDiary(journal.read(), frames, since) }
    }
    return diary
}

/** Where the night's diary starts: the noon of its evening. */
internal fun nightStart(evening: LocalDate, zone: TimeZone): Instant = LocalDateTime(evening, LocalTime(NOON, 0)).toInstant(zone)

/** One event as the design's rows: its time, its icon, what happened and the detail under it. */
@Composable
private fun DiaryRow(event: DiaryEvent, line: DiaryLine, zone: TimeZone, last: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                clockTime(event.at, zone),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
            )
            Icon(
                line.icon,
                contentDescription = null,
                tint = line.tint ?: colors.onSurface,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(line.title, style = MaterialTheme.typography.bodyLarge)
                line.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
        }
        if (!last) HorizontalDivider(color = colors.outline)
    }
}

private const val NOON = 12
