// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.yacana.domain.sky.Night
import app.yacana.ui.YacanaIcons
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.night_legend_moon
import app.yacana.ui.resources.night_legend_night
import app.yacana.ui.resources.night_legend_twilight
import app.yacana.ui.resources.night_moon_down_all
import app.yacana.ui.resources.night_moon_rises
import app.yacana.ui.resources.night_moon_sets
import app.yacana.ui.resources.night_moon_up_all
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The night from [sunset] to [sunrise], as the design's card draws it: the sky darkening through
 * the twilights and lightening again, the dark hours without the Moon boxed, a track for the Moon
 * with its rise or set, the hours under them, and a legend.
 */
@Composable
internal fun NightBar(night: Night, sunset: Instant, sunrise: Instant, zone: TimeZone) {
    val span = (sunrise - sunset).inWholeSeconds.toFloat()
    fun at(time: Instant) = ((time - sunset).inWholeSeconds / span).coerceIn(0f, 1f)
    val colors = SkyColors(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerLowest)
    val moonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = MOON_ALPHA)
    val moonUp = night.moonUp.mapNotNull { up -> (at(up.start) to at(up.endExclusive)).takeIf { it.second > it.first } }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val width = maxWidth
        fun x(fraction: Float): Dp = width * fraction
        // The sky, with the dark hours without the Moon boxed.
        Box(Modifier.fillMaxWidth().height(BOX_HEIGHT)) {
            Box(
                Modifier
                    .padding(top = (BOX_HEIGHT - SKY_HEIGHT) / 2)
                    .fillMaxWidth()
                    .height(SKY_HEIGHT)
                    .background(Brush.horizontalGradient(colorStops = skyStops(night, ::at, colors)), RoundedCornerShape(4.dp)),
            )
            night.moonlessDark.forEach { dark ->
                Box(
                    Modifier
                        .offset(x = x(at(dark.start)))
                        .width(x(at(dark.endExclusive) - at(dark.start)))
                        .height(BOX_HEIGHT)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
                )
            }
        }
    }
    MoonTrack(moonUp, night, sunset, sunrise, ::at, moonColor)
    HourTicks(sunset, sunrise, zone, ::at)
    Legend(colors, moonColor, moonText(night, sunset, sunrise, zone))
}

/** The Moon's track: a line where it is up, and its icon where it rises or sets. */
@Composable
private fun MoonTrack(
    moonUp: List<Pair<Float, Float>>,
    night: Night,
    sunset: Instant,
    sunrise: Instant,
    at: (Instant) -> Float,
    moonColor: Color,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val card = MaterialTheme.colorScheme.surfaceContainer
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp).height(MOON_ICON)) {
        val width = maxWidth
        val lineTop = (MOON_ICON - MOON_LINE) / 2
        Box(Modifier.padding(top = lineTop).fillMaxWidth().height(MOON_LINE).background(track, RoundedCornerShape(2.dp)))
        moonUp.forEach { (from, to) ->
            Box(
                Modifier
                    .offset(x = width * from, y = lineTop)
                    .width(width * (to - from))
                    .height(MOON_LINE)
                    .background(moonColor, RoundedCornerShape(2.dp)),
            )
        }
        moonEvents(night, sunset, sunrise).forEach { (time, _) ->
            Icon(
                YacanaIcons.Night,
                contentDescription = null,
                tint = moonColor,
                modifier = Modifier.offset(x = width * at(time) - MOON_ICON / 2).size(MOON_ICON).background(card, RoundedCornerShape(50)),
            )
        }
    }
}

/** The even hours between sunset and sunrise, under where they fall. */
@Composable
private fun HourTicks(sunset: Instant, sunrise: Instant, zone: TimeZone, at: (Instant) -> Float) {
    val style = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val local = sunset.toLocalDateTime(zone)
    val ticks = generateSequence(LocalDateTime(local.date, LocalTime(local.hour, 0)).toInstant(zone) + 1.hours) { it + 1.hours }
        .takeWhile { it < sunrise }
        .filter { it.toLocalDateTime(zone).hour % 2 == 0 }
        .toList()
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        val width = maxWidth
        ticks.forEach { tick ->
            val label = tick.toLocalDateTime(zone).hour.toString()
            val textWidth = with(density) { measurer.measure(label, style).size.width.toDp() }
            Text(
                label,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = (width * at(tick) - textWidth / 2).coerceIn(0.dp, width - textWidth)),
            )
        }
    }
}

/** Twilight, night and the Moon, with its rise or set. */
@Composable
private fun Legend(colors: SkyColors, moonColor: Color, moon: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    FlowRow(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        @Composable
        fun item(swatch: Modifier, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
            Box(swatch)
            Text(text, style = style, color = muted, modifier = Modifier.padding(start = 4.dp))
        }
        item(
            Modifier.size(14.dp, 8.dp).background(Brush.horizontalGradient(listOf(colors.light, colors.night)), RoundedCornerShape(2.dp)),
            stringResource(Res.string.night_legend_twilight),
        )
        item(
            Modifier.size(
                14.dp,
                8.dp,
            ).background(colors.night, RoundedCornerShape(2.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
            stringResource(Res.string.night_legend_night),
        )
        item(
            Modifier.size(14.dp, MOON_LINE).background(moonColor, RoundedCornerShape(2.dp)),
            stringResource(Res.string.night_legend_moon, moon),
        )
    }
}

/** The Moon's rises and sets between [sunset] and [sunrise], in order: true for a rise. */
private fun moonEvents(night: Night, sunset: Instant, sunrise: Instant): List<Pair<Instant, Boolean>> = night.moonUp
    .flatMap { listOf(it.start to true, it.endExclusive to false) }
    .filter { (time, _) -> time > sunset && time < sunrise }
    .sortedBy { it.first }

/** "rises 00:39", "sets 23:10", both, or whether it is up all night or not at all. */
@Composable
private fun moonText(night: Night, sunset: Instant, sunrise: Instant, zone: TimeZone): String {
    val events = moonEvents(night, sunset, sunrise)
    if (events.isEmpty()) {
        val up = night.moonUp.any { it.start <= sunset && it.endExclusive >= sunrise }
        return stringResource(if (up) Res.string.night_moon_up_all else Res.string.night_moon_down_all)
    }
    return events.map { (time, rise) ->
        stringResource(if (rise) Res.string.night_moon_rises else Res.string.night_moon_sets, clockTime(time, zone))
    }
        .joinToString(", ")
}

/** The sky's colours: the light at sunset and sunrise, and the dark of the night. */
private class SkyColors(twilight: Color, val night: Color) {
    val light = lerp(night, twilight, LIGHT)
    val civil = lerp(night, twilight, CIVIL)
    val nautical = lerp(night, twilight, NAUTICAL)
}

/** The gradient's stops at each twilight that happens: lighter at sunset and sunrise, dark in the astronomical night. */
private fun skyStops(night: Night, at: (Instant) -> Float, colors: SkyColors): Array<Pair<Float, Color>> = listOfNotNull(
    0f to colors.light,
    night.civilDusk?.let { at(it) to colors.civil },
    night.nauticalDusk?.let { at(it) to colors.nautical },
    night.astronomicalDusk?.let { at(it) to colors.night },
    night.astronomicalDawn?.let { at(it) to colors.night },
    night.nauticalDawn?.let { at(it) to colors.nautical },
    night.civilDawn?.let { at(it) to colors.civil },
    1f to colors.light,
).toTypedArray()

private val BOX_HEIGHT = 20.dp
private val SKY_HEIGHT = 14.dp
private val MOON_ICON = 16.dp
private val MOON_LINE = 4.dp
private const val MOON_ALPHA = 0.75f
private const val LIGHT = 0.7f
private const val CIVIL = 0.42f
private const val NAUTICAL = 0.18f
