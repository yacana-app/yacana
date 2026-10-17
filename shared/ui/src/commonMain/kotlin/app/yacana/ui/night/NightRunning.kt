// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.yacana.domain.focus.AutofocusResult
import app.yacana.domain.imaging.CaptureActivity
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.HistoryFrame
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Pace
import app.yacana.domain.sequence.RunOverview
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.overview
import app.yacana.domain.sky.Ephemerides
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.imaging.toImageBitmap
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.run_alt
import app.yacana.ui.resources.run_change
import app.yacana.ui.resources.run_dawn
import app.yacana.ui.resources.run_discarded
import app.yacana.ui.resources.run_downloading
import app.yacana.ui.resources.run_end
import app.yacana.ui.resources.run_exposing
import app.yacana.ui.resources.run_filter
import app.yacana.ui.resources.run_frame
import app.yacana.ui.resources.run_gain
import app.yacana.ui.resources.run_good
import app.yacana.ui.resources.run_hfr_stars
import app.yacana.ui.resources.run_in
import app.yacana.ui.resources.run_last
import app.yacana.ui.resources.run_next
import app.yacana.ui.resources.run_next_autofocus
import app.yacana.ui.resources.run_panel
import app.yacana.ui.resources.run_park
import app.yacana.ui.resources.run_pause
import app.yacana.ui.resources.run_paused
import app.yacana.ui.resources.run_progress
import app.yacana.ui.resources.run_resume
import app.yacana.ui.resources.sequence_stop
import app.yacana.ui.sequence.step
import app.yacana.ui.sequence.stepText
import app.yacana.ui.theme.LocalPhotoFilter
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * "Night · sequence running": all of the run at a glance, the target, the frame in
 * progress, the progress against dawn, the last frame and what comes next. With live stacking on,
 * the last frame's card shows the [stack] and opens it ([onStack]), as "with stacking".
 */
@Composable
@Suppress("LongParameterList") // The run, the capture, the history, the focus, the site and the time.
fun RunningNight(
    run: SequenceRun,
    activity: CaptureActivity,
    frames: List<HistoryFrame>,
    autofocus: AutofocusResult?,
    site: Location?,
    dawn: Instant?,
    now: Instant,
    zone: TimeZone,
    dither: DitherOptions,
    stack: StackCard? = null,
    pace: Pace = Pace(),
    onStack: () -> Unit = {},
) {
    val overview = run.overview(now, dither, pace)
    TargetCard(overview, site, now)
    FrameCard(run, overview, activity, now)
    ProgressCard(overview, dawn, zone)
    LastFrameCard(frames, autofocus, stack, onStack)
    NextCard(run, overview, autofocus, now, zone)
}

/** The design's buttons under the run: "Pause", or "Resume" once paused, and "Hold to stop". */
@Composable
fun RunningButtons(paused: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SurfaceButton(
            stringResource(if (paused) Res.string.run_resume else Res.string.run_pause),
            if (paused) onResume else onPause,
            Modifier.weight(1f),
            icon = if (paused) YacanaIcons.Play else YacanaIcons.Pause,
        )
        HoldButton(
            stringResource(Res.string.sequence_stop),
            null,
            onStop,
            Modifier.weight(STOP_WEIGHT),
            danger = true,
            icon = YacanaIcons.Stop,
        )
    }
}

/** The target's card: its name and how high it is, rising or setting; its panel and filter. */
@Composable
internal fun TargetCard(overview: RunOverview, site: Location?, now: Instant) {
    val name = overview.name ?: return
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val field = overview.target
            if (field != null && site != null) {
                val altitude = Ephemerides.altitude(field.position, now, site)
                val rising = Ephemerides.altitude(field.position, now + RISING, site) > altitude
                Muted(stringResource(Res.string.run_alt, altitude.roundToInt()))
                Icon(
                    if (rising) YacanaIcons.ArrowUp else YacanaIcons.ArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp).size(16.dp),
                )
            }
        }
        val parts =
            listOfNotNull(
                overview.panel?.let { stringResource(Res.string.run_panel, it, overview.panels) },
                overview.exposures?.filter?.let { stringResource(Res.string.run_filter, it) },
            )
        if (parts.isNotEmpty()) Muted(parts.joinToString(" · "))
    }
}

/**
 * The frame in progress, as the design's ring: the seconds left of the exposure, "Frame 34 of 120"
 * and its settings; or the step in progress, or the pause.
 */
@Composable
internal fun FrameCard(run: SequenceRun, overview: RunOverview, activity: CaptureActivity, now: Instant) {
    val exposures = overview.exposures
    val exposing = activity as? CaptureActivity.Exposing
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val elapsed = exposing?.let { (now - it.started).inWholeMilliseconds / MILLIS / it.seconds }
            val left = exposing?.let { (it.seconds - (now - it.started).inWholeMilliseconds / MILLIS).coerceAtLeast(0.0) }
            Ring(elapsed?.toFloat()?.coerceIn(0f, 1f) ?: 0f, left?.let { "${it.roundToInt()} s" }.orEmpty())
            Column {
                val doing =
                    when {
                        run.status == RunStatus.Paused -> stringResource(Res.string.run_paused)
                        exposures == null -> run.step()?.let { stepText(it) }.orEmpty()
                        exposing != null -> stringResource(Res.string.run_exposing, seconds(exposures.exposureSeconds))
                        activity == CaptureActivity.Downloading -> stringResource(Res.string.run_downloading)
                        else -> null
                    }
                doing?.let { Muted(it) }
                if (exposures != null) {
                    Text(
                        stringResource(Res.string.run_frame, overview.frame, exposures.count),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val settings =
                        listOfNotNull(exposures.filter, exposures.gain?.let { stringResource(Res.string.run_gain, it) })
                    if (settings.isNotEmpty()) Muted(settings.joinToString(" · "))
                }
            }
        }
    }
}

/** How far along the sequence is, when it ends and when the astronomical dawn comes; the end in red after it. */
@Composable
internal fun ProgressCard(overview: RunOverview, dawn: Instant?, zone: TimeZone) {
    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.run_progress, (overview.progress * PERCENT).roundToInt()), modifier = Modifier.weight(1f))
            val late = dawn != null && overview.end > dawn
            Text(
                stringResource(Res.string.run_end, clockTime(overview.end, zone)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { overview.progress.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
        if (dawn != null) IconLine(YacanaIcons.Sunrise, stringResource(Res.string.run_dawn, clockTime(dawn, zone)))
    }
}

/** The design's ring: the part of the exposure done, and the seconds left inside. */
@Composable
private fun Ring(done: Float, text: String) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(60.dp)) {
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, FULL_TURN, useCenter = false, style = stroke)
            if (done > 0f) drawArc(fill, -QUARTER_TURN, FULL_TURN * done, useCenter = false, style = stroke)
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Muted(text, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** "60" or "0.5": an exposure's seconds. */
@Composable
private fun seconds(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else formatDecimal(value, 1, decimalComma())

private val RISING = 10.minutes
private const val MILLIS = 1_000.0
private const val PERCENT = 100
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val STOP_WEIGHT = 1.45f
