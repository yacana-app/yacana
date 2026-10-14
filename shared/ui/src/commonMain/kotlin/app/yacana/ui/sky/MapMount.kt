// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.BelowHorizonException
import app.yacana.domain.equipment.MountMediator
import app.yacana.domain.equipment.MountState
import app.yacana.domain.sky.SkyView
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.CentringState
import app.yacana.domain.solver.CentringStep
import app.yacana.ports.SkyPosition
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.equipment.CentringResult
import app.yacana.ui.equipment.centringStepText
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatDm
import app.yacana.ui.format.formatOffset
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.centring_stop
import app.yacana.ui.resources.mount_below_horizon
import app.yacana.ui.resources.mount_go_and_centre
import app.yacana.ui.resources.sky_centring_title
import app.yacana.ui.resources.sky_go_hint
import app.yacana.ui.resources.sky_go_only
import app.yacana.ui.resources.sky_going_left
import app.yacana.ui.resources.sky_going_then
import app.yacana.ui.resources.sky_going_title
import app.yacana.ui.resources.sky_telescope
import app.yacana.ui.resources.sky_telescope_parked
import app.yacana.ui.resources.sky_telescope_still
import app.yacana.ui.theme.LocalTelescopeColor
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Clock

/*
 * The mount on the sky map, as "Sky · map with the mount", "· slewing" and "· pad":
 * where the telescope points and what it is doing, "Go and centre" and "Go only" to the framing,
 * each step on the way with "Stop", and the pad.
 */

/**
 * [position] gliding from one reading to the next for a second, as the mount reads it every
 * second, so that its frame crosses the map without jumps; along the sphere, so that right
 * ascension's turn from 24 h to 0 h is no jump either.
 */
@Composable
internal fun glided(position: SkyPosition): SkyPosition {
    val ra = position.raHours * PI / HALF_DAY_HOURS
    val dec = position.decDeg * PI / HALF_TURN_DEG
    val glide = tween<Float>(GLIDE_MILLIS, easing = LinearEasing)
    val x by animateFloatAsState((cos(dec) * cos(ra)).toFloat(), glide)
    val y by animateFloatAsState((cos(dec) * sin(ra)).toFloat(), glide)
    val z by animateFloatAsState(sin(dec).toFloat(), glide)
    val hours = (atan2(y.toDouble(), x.toDouble()) * HALF_DAY_HOURS / PI).mod(2 * HALF_DAY_HOURS)
    return SkyPosition(hours, atan2(z.toDouble(), hypot(x.toDouble(), y.toDouble())) * HALF_TURN_DEG / PI)
}

/**
 * "Telescope", in its colour, as the designs' label beside the photos', so it takes no room of
 * the map; parked, or still when it could track, it says so, and a tap puts it right.
 */
@Composable
internal fun TelescopeLabel(state: MountState, mount: MountMediator, act: (suspend () -> Unit) -> Unit) {
    val (text, fix) =
        when {
            state.slewing -> stringResource(Res.string.sky_telescope) to null

            state.atPark && mount.capabilities.canUnpark -> stringResource(Res.string.sky_telescope_parked) to suspend { mount.unpark() }

            !state.tracking && mount.capabilities.canSetTracking ->
                stringResource(Res.string.sky_telescope_still) to suspend { mount.setTracking(true) }

            else -> stringResource(Res.string.sky_telescope) to null
        }
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalTelescopeColor.current,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .then(if (fix != null) Modifier.clickable { act(fix) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/**
 * Under the map with a mount: while centring, or going with "Go only" to [goingOnly], each step
 * with "Stop"; otherwise "Go and centre" and "Go only" to the framing at [target], with how the
 * last centring ended, or why the mount did not go.
 */
@Composable
@Suppress("LongParameterList") // The mount, where it is and where it goes, and what the map does with it.
internal fun MountPanel(
    mount: MountMediator,
    telescope: SkyPosition?,
    target: SkyPosition,
    centring: Centring,
    centringState: CentringState,
    goingOnly: SkyPosition?,
    onGoOnly: (SkyPosition) -> Unit,
    failure: Exception?,
    clock: Clock,
    act: (suspend () -> Unit) -> Unit,
) {
    val working = centringState as? CentringState.Working
    when {
        working != null -> {
            val options = centring.options
            val text =
                if (working.step == CentringStep.Slewing) {
                    stringResource(Res.string.sky_going_left, distance(telescope, working.target)) + " " +
                        stringResource(
                            Res.string.sky_going_then,
                            formatDecimal(options.centringExposureSeconds, 0, decimalComma()),
                            formatOffset(options.centringToleranceArcsec),
                        )
                } else {
                    centringStepText(working)
                }
            Going(
                stringResource(Res.string.sky_centring_title, working.attempt),
                text,
                working.target,
                telescope.takeIf { working.step == CentringStep.Slewing },
                centring::stop,
            )
        }

        goingOnly != null ->
            Going(
                stringResource(Res.string.sky_going_title),
                stringResource(Res.string.sky_going_left, distance(telescope, goingOnly)),
                goingOnly,
                telescope,
            ) { act { mount.stop() } }

        else -> GoButtons(target, centring, centringState, failure, mount, clock, act, onGoOnly)
    }
}

/** "Go and centre", the main one, and "Go only", to the framing; how the last centring ended; why the mount did not go. */
@Composable
@Suppress("LongParameterList") // Where to, and how.
private fun GoButtons(
    target: SkyPosition,
    centring: Centring,
    centringState: CentringState,
    failure: Exception?,
    mount: MountMediator,
    clock: Clock,
    act: (suspend () -> Unit) -> Unit,
    onGoOnly: (SkyPosition) -> Unit,
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentButton(onClick = { centring.centre(target) }, modifier = Modifier.weight(1f)) {
                Icon(YacanaIcons.Crosshair, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(stringResource(Res.string.mount_go_and_centre))
            }
            SurfaceButton(stringResource(Res.string.sky_go_only), {
                act {
                    val there = mount.toMount(target, clock.now())
                    mount.goTo(there.raHours, there.decDeg)
                    onGoOnly(target)
                }
            }, Modifier.width(GO_ONLY_WIDTH))
        }
        Text(
            stringResource(Res.string.sky_go_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        CentringResult(centringState)
        failure?.let {
            val text =
                when (it) {
                    is BelowHorizonException -> stringResource(
                        Res.string.mount_below_horizon,
                        formatDm(it.target.altitudeDeg),
                        formatDm(it.limitDeg),
                    )

                    else -> it.message?.let { message -> errorText(message) } ?: it::class.simpleName.orEmpty()
                }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * On the way, as "Sky · map · slewing": what it does now, with "Stop", and while the
 * telescope is still [at] somewhere else, how much of the way to [target] it has gone.
 */
@Composable
private fun Going(title: String, text: String, target: SkyPosition, at: SkyPosition?, onStop: () -> Unit) {
    val left = at?.let { SkyView.separation(it, target) }
    // The way from where it was when it set off: the farthest it has been from the target.
    val way = remember(target) { mutableStateOf(0.0) }
    if (left != null && left > way.value) way.value = left
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            SettingChip(stringResource(Res.string.centring_stop), onClick = onStop, icon = YacanaIcons.Stop)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (left != null && way.value > 0) {
            LinearProgressIndicator(
                progress = { (1 - left / way.value).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                // The way still to go in the card's grey, not in the theme's gold.
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

/** How far the telescope [at] still is from [target]: whole degrees from one on, as "14°", and closer as `18′ 12″`; a dash without it. */
private fun distance(at: SkyPosition?, target: SkyPosition): String {
    val degrees = at?.let { SkyView.separation(it, target) } ?: return "—"
    return if (degrees >= 1) "${degrees.roundToInt()}°" else formatOffset(max(degrees, 0.0) * ARCSEC_PER_DEGREE)
}

private const val GLIDE_MILLIS = 1000
private const val HALF_DAY_HOURS = 12.0
private const val HALF_TURN_DEG = 180.0
private const val ARCSEC_PER_DEGREE = 3600.0
private val GO_ONLY_WIDTH = 112.dp
