// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.polar.PolarAlignmentState
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.SafetyOptions
import app.yacana.domain.sequence.NightPlan
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.overview
import app.yacana.domain.session.DiaryEvent
import app.yacana.domain.session.GuardState
import app.yacana.domain.session.nightSummary
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Night
import app.yacana.ui.AppServices
import app.yacana.ui.Section
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.Touch
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.months
import app.yacana.ui.resources.night_best_moonless
import app.yacana.ui.resources.night_best_with_moon
import app.yacana.ui.resources.night_date
import app.yacana.ui.resources.night_moon
import app.yacana.ui.resources.night_no_astronomical
import app.yacana.ui.resources.night_no_location_phone
import app.yacana.ui.resources.night_no_location_typed
import app.yacana.ui.resources.night_no_profile
import app.yacana.ui.resources.night_open_equipment
import app.yacana.ui.resources.night_open_profile
import app.yacana.ui.resources.night_other
import app.yacana.ui.resources.night_span
import app.yacana.ui.resources.night_start
import app.yacana.ui.resources.night_sun_up
import app.yacana.ui.resources.night_sunrise
import app.yacana.ui.resources.night_sunset
import app.yacana.ui.resources.weekdays
import app.yacana.ui.sequence.tonightSequence
import app.yacana.ui.sky.planNight
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Night section. Before the night, as "Night · before starting": tonight's card, with the
 * Sun, the Moon and the dark hours without the Moon at the profile's site, then "Prepare
 * the night" and the button that starts the night. While a sequence goes on, as "Night ·
 * sequence running". While the session's guard reconnects or stops safely, its
 * warning, as "Night · alert".
 */
@Composable
fun NightScreen(services: AppServices, open: (Screen) -> Unit) {
    val equipment = services.equipment
    val runner = services.sequence
    val guard = services.guard
    val clock = services.clock
    val zone = services.zone
    val state by equipment.state.collectAsState()
    val guardState = guard?.state?.collectAsState()?.value ?: GuardState.Watching
    val run by runner.state.collectAsState()
    val targets by services.targets.targets.collectAsState()
    val triggers by services.targets.triggers.collectAsState()
    val darks by services.targets.darks.collectAsState()
    val heater by services.targets.heater.collectAsState()
    val advanced by services.targets.advanced.collectAsState()
    val polar by services.polarAlignment.state.collectAsState()
    val lastPolar by services.polarAlignment.last.collectAsState()
    val focus by services.autofocus.last.collectAsState()
    val capture by services.capture.state.collectAsState()
    val frames by services.capture.history.frames.collectAsState()
    val profile = state.active
    val site = profile?.location
    val going = run?.status == RunStatus.Running || run?.status == RunStatus.Paused
    // While a sequence goes on, its counters tick every second.
    var tick by remember { mutableStateOf(clock.now()) }
    LaunchedEffect(going) {
        while (going) {
            tick = clock.now()
            delay(1.seconds)
        }
    }
    val now = if (going) tick else clock.now()
    val evening = evening(now, zone())
    // Another night on the card, from its calendar; tonight's when there is none.
    var other by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarOpen by remember { mutableStateOf(false) }
    val shown = other?.let(LocalDate::parse) ?: evening
    // A dozen searches of a few milliseconds each, once for the evening and the site.
    val night = remember(shown, site) { site?.let { Ephemerides.night(shown, it) } }
    val planned = remember(site, now.epochSeconds / SECONDS_PER_MINUTE) { site?.let { planNight(now, zone(), it) } }
    val dither = profile?.options?.dither ?: DitherOptions()
    val plan = NightPlan.of(targets, now, planned?.astronomicalDusk, planned?.astronomicalDawn, dither)
    val current = run?.takeIf { going }
    // After the run, its summary, until its ✕.
    var closed by remember { mutableStateOf<SequenceRun?>(null) }
    val ended = run?.takeIf { !going && it !== closed }
    val since = nightStart(evening, zone())
    val diary = rememberDiary(services.journal, frames, since, run?.status)
    // The summary is of the last run, from its start.
    val runStart = diary.lastOrNull { it is DiaryEvent.Started }?.at ?: since
    if (calendarOpen) {
        BottomSheet({ calendarOpen = false }) {
            NightCalendar(shown, evening) { date ->
                other = date.takeIf { it != evening }?.toString()
                calendarOpen = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SessionAlert(
                guardState,
                run,
                profile?.name.orEmpty(),
                profile?.options?.safety ?: SafetyOptions(),
                clock,
                onRetry = { guard?.retryNow() },
                onDetails = { open(Screen.Root(Section.Equipment)) },
                onStop = { runner.stop() },
                onDismiss = { guard?.dismiss() },
            ) {
                current?.let {
                    val overview = it.overview(now, dither)
                    TargetCard(overview, site, now)
                    ProgressCard(overview, planned?.astronomicalDawn, zone())
                }
            }
            when {
                ended != null -> {
                    val runDiary = diary.filter { it.at >= runStart }
                    NightSummaryCards(nightSummary(runDiary, frames, runStart), runDiary, evening, zone()) { closed = ended }
                }

                current == null -> Before(services, night, open, onCalendar = { calendarOpen = true }) {
                    PrepareCard(state, polar as? PolarAlignmentState.Adjusting ?: lastPolar, focus, targets, plan, now, open) {
                        equipment.connect()
                    }
                }

                guardState == GuardState.Watching -> {
                    val stack = rememberStackCard(services.capture.stacking, profile?.options?.stacking)
                    RunningNight(current, capture.activity, frames, focus, site, planned?.astronomicalDawn, now, zone(), dither, stack) {
                        services.display.setShowStack(true)
                        open(Screen.Root(Section.Imaging))
                    }
                }
            }
        }
        when {
            current != null && guardState == GuardState.Watching -> RunningButtons(
                paused = current.status == RunStatus.Paused,
                onPause = { runner.pause() },
                onResume = { runner.resume() },
                onStop = { runner.stop() },
            )

            ended != null -> SummaryButtons(onDiary = { open(Screen.NightDiary) }, onExport = { services.logExport.export() })

            current == null && profile != null -> AccentButton(
                onClick = { runner.start(advanced ?: tonightSequence(targets, triggers, state, darks, heater)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = Touch.Main),
                enabled = state.connection is Connection.Connected && (advanced != null || targets.any { it.exposures.isNotEmpty() }),
            ) {
                Icon(YacanaIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(Res.string.night_start), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Before the night: tonight's card, or what it needs, and then [prepare]; its calendar, [onCalendar]. */
@Composable
private fun Before(services: AppServices, night: Night?, open: (Screen) -> Unit, onCalendar: () -> Unit, prepare: @Composable () -> Unit) {
    val profile = services.equipment.state.collectAsState().value.active
    val site = profile?.location
    val zone = services.zone
    SectionCard {
        when {
            profile == null -> Missing(
                stringResource(Res.string.night_no_profile),
                stringResource(Res.string.night_open_equipment),
            ) {
                open(Screen.Root(Section.Equipment))
            }

            site == null -> {
                val phone = profile.options.locationFromPhone
                Missing(
                    stringResource(if (phone) Res.string.night_no_location_phone else Res.string.night_no_location_typed),
                    stringResource(Res.string.night_open_profile),
                ) { open(Screen.Profile) }
            }

            else -> night?.let { Tonight(it, zone(), onCalendar) }
        }
    }
    if (profile != null) prepare()
}

/** The evening whose night it is: before noon, still last night's. */
private fun evening(now: Instant, zone: TimeZone): LocalDate {
    val local = now.toLocalDateTime(zone)
    return if (local.hour < NOON) local.date.minus(1, DateTimeUnit.DAY) else local.date
}

/** What the card needs and how to get it. */
@Composable
private fun Missing(text: String, action: String, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
}

/**
 * As the design: "Friday, October 2 · Moon 23 %", with the calendar that shows another night,
 * then the dark hours without the Moon, sunset and sunrise, and under them the night's bar.
 */
@Composable
private fun Tonight(night: Night, zone: TimeZone, onCalendar: () -> Unit) {
    val weekdays = stringArrayResource(Res.array.weekdays)
    val months = stringArrayResource(Res.array.months)
    val date = night.evening
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.night_date, weekdays[date.dayOfWeek.ordinal], date.day, months[date.month.ordinal]),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            YacanaIcons.Night,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.night_moon, (night.moonIllumination * PERCENT).roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        IconButton(onClick = onCalendar) { Icon(YacanaIcons.Calendar, contentDescription = stringResource(Res.string.night_other)) }
    }
    Headline(night, zone)
    val sunset = night.sunset
    val sunrise = night.sunrise
    if (sunset != null && sunrise != null && sunset < sunrise) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SunTime(YacanaIcons.Sunset, stringResource(Res.string.night_sunset, clockTime(sunset, zone)), Modifier.weight(1f))
            SunTime(YacanaIcons.Sunrise, stringResource(Res.string.night_sunrise, clockTime(sunrise, zone)), iconFirst = false)
        }
        NightBar(night, sunset, sunrise, zone)
    }
}

/**
 * The best of the night, under a star: the longest dark stretch without the Moon, or the
 * astronomical night if the Moon is up all of it; or why there is no dark night.
 */
@Composable
private fun Headline(night: Night, zone: TimeZone) {
    val astronomical = night.astronomical
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (astronomical == null) {
        Text(
            stringResource(if (night.sunset == null) Res.string.night_sun_up else Res.string.night_no_astronomical),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        return
    }
    val moonless = night.moonlessDark.maxByOrNull { it.endExclusive - it.start }
    val span = moonless ?: astronomical
    // On one line, the hours at the right; under it on a phone too narrow for both.
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            Icon(YacanaIcons.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (moonless != null) Res.string.night_best_moonless else Res.string.night_best_with_moon),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, end = 8.dp),
            )
        }
        Text(
            stringResource(
                Res.string.night_span,
                clockTime(span.start, zone),
                clockTime(span.endExclusive, zone),
                hoursAndMinutes(span.endExclusive - span.start),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

/** "Sunset 20:10" with its icon, which goes after the text for sunrise, at the right. */
@Composable
private fun SunTime(icon: ImageVector, text: String, modifier: Modifier = Modifier, iconFirst: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val image = @Composable {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        if (iconFirst) image()
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
        if (!iconFirst) image()
    }
}

/** "7 h 25 min". */
internal fun hoursAndMinutes(duration: Duration): String {
    val minutes = duration.inWholeMinutes
    return "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
}

private const val NOON = 12
private const val SECONDS_PER_MINUTE = 60
private const val PERCENT = 100
private const val MINUTES_PER_HOUR = 60
