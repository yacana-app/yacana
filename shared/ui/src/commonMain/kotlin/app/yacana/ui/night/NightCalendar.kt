// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.calendar_months
import app.yacana.ui.resources.month_next
import app.yacana.ui.resources.month_previous
import app.yacana.ui.resources.night_month
import app.yacana.ui.resources.night_other
import app.yacana.ui.resources.night_tonight_again
import app.yacana.ui.resources.weekday_initials
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The design "Night · another night": a month, from [shown]'s, with [tonight] outlined and [shown]
 * filled; a day tapped is chosen at once, and "Back to tonight" goes back to [tonight]. The
 * days are picked, never typed. Tonight is the app's, not the phone's today: after
 * midnight it is still yesterday's evening.
 */
@Composable
internal fun NightCalendar(shown: LocalDate, tonight: LocalDate, onChoose: (LocalDate) -> Unit) {
    var first by rememberSaveable(shown) { mutableStateOf(LocalDate(shown.year, shown.month, 1).toString()) }
    val month = LocalDate.parse(first)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(stringResource(Res.string.night_other), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { first = month.plus(-1, DateTimeUnit.MONTH).toString() }) {
            Icon(YacanaIcons.ChevronLeft, contentDescription = stringResource(Res.string.month_previous))
        }
        Text(
            stringResource(Res.string.night_month, stringArrayResource(Res.array.calendar_months)[month.month.ordinal], month.year),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { first = month.plus(1, DateTimeUnit.MONTH).toString() }) {
            Icon(YacanaIcons.ChevronRight, contentDescription = stringResource(Res.string.month_next))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        stringArrayResource(Res.array.weekday_initials).forEach { initial ->
            Text(initial, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center)
        }
    }
    Days(month, shown, tonight, onChoose)
    SurfaceButton(
        stringResource(Res.string.night_tonight_again),
        onClick = { onChoose(tonight) },
        modifier = Modifier.fillMaxWidth(),
        icon = YacanaIcons.CalendarEvent,
        enabled = shown != tonight,
    )
}

/** The month's days in weeks from Monday, as the design's grid. */
@Composable
private fun Days(month: LocalDate, shown: LocalDate, tonight: LocalDate, onChoose: (LocalDate) -> Unit) {
    val blanks = month.dayOfWeek.ordinal
    val days = month.daysUntil(month.plus(1, DateTimeUnit.MONTH))
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat((blanks + days + WEEK - 1) / WEEK) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(WEEK) { column ->
                    val day = week * WEEK + column - blanks
                    val date = if (day in 0 until days) month.plus(day, DateTimeUnit.DAY) else null
                    Box(Modifier.weight(1f).height(Touch.Min).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (date != null) Day(date, chosen = date == shown, tonight = date == tonight, accent) { onChoose(date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Day(date: LocalDate, chosen: Boolean, tonight: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .widthIn(max = DAY_WIDTH)
            .fillMaxSize()
            .clip(shape)
            .background(if (chosen) accent else Color.Transparent)
            .border(1.dp, if (tonight && !chosen) accent else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${date.day}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (chosen) FontWeight.Medium else null,
        )
    }
}

private const val WEEK = 7

// A day's pill, not wider in landscape than in portrait.
private val DAY_WIDTH = 56.dp
