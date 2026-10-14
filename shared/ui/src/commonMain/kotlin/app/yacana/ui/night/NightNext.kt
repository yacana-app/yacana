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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
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
import app.yacana.domain.imaging.LiveStacking
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.StackingOptions
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
import app.yacana.ui.imaging.rememberStackThumbnail
import app.yacana.ui.imaging.stackTime
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
import app.yacana.ui.resources.run_last_short
import app.yacana.ui.resources.run_next
import app.yacana.ui.resources.run_next_autofocus
import app.yacana.ui.resources.run_panel
import app.yacana.ui.resources.run_park
import app.yacana.ui.resources.run_pause
import app.yacana.ui.resources.run_paused
import app.yacana.ui.resources.run_progress
import app.yacana.ui.resources.run_resume
import app.yacana.ui.resources.run_stack
import app.yacana.ui.resources.sequence_stop
import app.yacana.ui.sequence.step
import app.yacana.ui.sequence.stepText
import app.yacana.ui.theme.LocalPhotoFilter
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The last light: its thumbnail, whether it was kept, its HFR and stars, and the HFR of the last ones with the last autofocus. */
@Composable
internal fun LastFrameCard(frames: List<HistoryFrame>, autofocus: AutofocusResult?, stack: StackCard? = null, onStack: () -> Unit = {}) {
    val lights = frames.filter { it.type == FrameType.Light }
    val last = lights.lastOrNull() ?: return
    SectionCard(onClick = if (stack != null) onStack else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val frameBitmap = remember(last.thumbnail) { last.thumbnail?.toImageBitmap() }
            val bitmap = if (stack != null) stack.thumbnail else frameBitmap
            Box(
                Modifier.size(
                    width = 64.dp,
                    height = 48.dp,
                ).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                if (bitmap !=
                    null
                ) {
                    Image(
                        bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                        colorFilter = LocalPhotoFilter.current,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                if (stack != null) {
                    StackLines(stack, last)
                    HfrLine(lights.takeLast(SPARK_FRAMES), autofocus?.at)
                    return@Column
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.run_last), modifier = Modifier.weight(1f))
                    val good = last.discarded == null
                    Icon(
                        if (good) YacanaIcons.CircleCheck else YacanaIcons.CircleX,
                        contentDescription = null,
                        tint = if (good) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(if (good) Res.string.run_good else Res.string.run_discarded),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Muted(stringResource(Res.string.run_hfr_stars, formatDecimal(last.hfr, 1, decimalComma()), last.stars))
                HfrLine(lights.takeLast(SPARK_FRAMES), autofocus?.at)
            }
        }
    }
}

/** The live stack as the night's card shows it: how many frames, their time and a thumbnail of it. */
data class StackCard(val frames: Int, val exposureSeconds: Double, val thumbnail: ImageBitmap?)

/** The live stack of [stacking] for the night's card, while stacking is on and it has a frame. */
@Composable
internal fun rememberStackCard(stacking: LiveStacking?, options: StackingOptions?): StackCard? {
    if (stacking == null || options?.live != true) return null
    val state by stacking.state.collectAsState()
    val thumbnail = rememberStackThumbnail(stacking, state, options.balanced)
    return state.info?.let { StackCard(it.frames, it.exposureSeconds, thumbnail) }
}

/**
 * The design "Night · sequence running (with stacking)": "Stack · 34 frames", its time and a
 * chevron, since the card opens it, and the last frame under it.
 */
@Composable
private fun StackLines(stack: StackCard, last: HistoryFrame) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(Res.plurals.run_stack, stack.frames, stack.frames), modifier = Modifier.weight(1f))
        Muted(stackTime(stack.exposureSeconds))
        Icon(
            YacanaIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val good = last.discarded == null
        Muted(stringResource(Res.string.run_last_short))
        Icon(
            if (good) YacanaIcons.CircleCheck else YacanaIcons.CircleX,
            contentDescription = null,
            tint = if (good) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp).size(14.dp),
        )
        Muted(
            stringResource(if (good) Res.string.run_good else Res.string.run_discarded) + " · " +
                stringResource(Res.string.run_hfr_stars, formatDecimal(last.hfr, 1, decimalComma()), last.stars),
        )
    }
}

/** The HFR of the last lights as a line, with a dashed mark where the last autofocus came. */
@Composable
private fun HfrLine(lights: List<HistoryFrame>, autofocus: Instant?) {
    if (lights.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(22.dp).padding(top = 4.dp)) {
        val low = lights.minOf { it.hfr }
        val span = (lights.maxOf { it.hfr } - low).takeIf { it > 0 } ?: 1.0
        val step = size.width / (lights.size - 1)
        fun y(hfr: Double) = (size.height * (1 - (hfr - low) / span)).toFloat()
        val focused = autofocus?.let { at -> lights.indexOfFirst { it.at > at } }?.takeIf { it > 0 }
        focused?.let { index ->
            val x = step * (index - 0.5f)
            drawLine(
                mark,
                Offset(x, 0f),
                Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
        }
        lights.zipWithNext().forEachIndexed { index, (a, b) ->
            drawLine(
                line,
                Offset(step * index, y(a.hfr)),
                Offset(step * (index + 1), y(b.hfr)),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * "Next": the next autofocus, if a trigger says when; the change to the next target; and when
 * the mount parks. Nothing when none is known.
 */
@Composable
internal fun NextCard(run: SequenceRun, overview: RunOverview, autofocus: AutofocusResult?, now: Instant, zone: TimeZone) {
    val every = run.sequence.root.triggers.filterIsInstance<Trigger.AutofocusAfterTime>().firstOrNull()
    val focusAt = every?.let { trigger -> autofocus?.at?.plus(trigger.minutes.minutes) }
    if (focusAt == null && overview.next == null && overview.park == null) return
    SectionCard {
        Muted(stringResource(Res.string.run_next))
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            focusAt?.let {
                val minutes = (it - now).inWholeMinutes.coerceAtLeast(0)
                NextLine(
                    YacanaIcons.Focus,
                    stringResource(Res.string.run_next_autofocus),
                    stringResource(Res.string.run_in, "$minutes min"),
                )
            }
            overview.next?.let { (name, at) ->
                NextLine(YacanaIcons.Target, stringResource(Res.string.run_change, name), clockTime(at, zone))
            }
            overview.park?.let { NextLine(YacanaIcons.Parking, stringResource(Res.string.run_park), clockTime(it, zone)) }
        }
    }
}

@Composable
private fun NextLine(icon: ImageVector, what: String, whenText: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(what, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Muted(whenText)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val SPARK_FRAMES = 20
