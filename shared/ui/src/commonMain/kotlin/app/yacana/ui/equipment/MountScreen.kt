// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.DriveRate
import app.yacana.alpaca.TelescopeAxis
import app.yacana.domain.equipment.BelowHorizonException
import app.yacana.domain.equipment.MountMediator
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.CentringFailure
import app.yacana.domain.solver.CentringState
import app.yacana.domain.solver.CentringStep
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.NumberPad
import app.yacana.ui.design.ReadingGrid
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatDm
import app.yacana.ui.format.formatDms
import app.yacana.ui.format.formatHms
import app.yacana.ui.format.formatOffset
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.centring_done
import app.yacana.ui.resources.centring_error
import app.yacana.ui.resources.centring_exposing
import app.yacana.ui.resources.centring_no_mount
import app.yacana.ui.resources.centring_not_solved
import app.yacana.ui.resources.centring_previous
import app.yacana.ui.resources.centring_slewing
import app.yacana.ui.resources.centring_solving
import app.yacana.ui.resources.centring_stop
import app.yacana.ui.resources.centring_title
import app.yacana.ui.resources.centring_too_far
import app.yacana.ui.resources.mount_below_horizon
import app.yacana.ui.resources.mount_centre_hint
import app.yacana.ui.resources.mount_coordinates_hint
import app.yacana.ui.resources.mount_direction_east
import app.yacana.ui.resources.mount_direction_north
import app.yacana.ui.resources.mount_direction_south
import app.yacana.ui.resources.mount_direction_west
import app.yacana.ui.resources.mount_find_home
import app.yacana.ui.resources.mount_go_and_centre
import app.yacana.ui.resources.mount_go_to
import app.yacana.ui.resources.mount_go_to_button
import app.yacana.ui.resources.mount_move_hint
import app.yacana.ui.resources.mount_park
import app.yacana.ui.resources.mount_park_hint
import app.yacana.ui.resources.mount_polar_alignment
import app.yacana.ui.resources.mount_rate_max
import app.yacana.ui.resources.mount_stop
import app.yacana.ui.resources.mount_sync
import app.yacana.ui.resources.mount_tracking_rate
import app.yacana.ui.resources.mount_tracking_switch
import app.yacana.ui.resources.mount_unpark
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.rate_king
import app.yacana.ui.resources.rate_lunar
import app.yacana.ui.resources.rate_sidereal
import app.yacana.ui.resources.rate_solar
import app.yacana.ui.resources.readout_alt
import app.yacana.ui.resources.readout_az
import app.yacana.ui.resources.readout_dec
import app.yacana.ui.resources.readout_ra
import app.yacana.ui.resources.role_mount
import app.yacana.ui.solving.solveFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong

/** Sidereal rate in degrees per second, the unit of manual moves. */
private const val SIDEREAL = 15.041 / 3600

/** Manual move rates as multiples of the sidereal rate (designs, Equipment · mount); the last one is the mount's maximum. */
private val RATES = listOf(1, 8, 64)

/**
 * The mount, as its design, "Equipment · mount": its state and position;
 * a pad that moves while a direction is held, with stop in the middle, and the move rates; then
 * go to (and centre), sync, park (held, not tapped) and tracking. While [centring] works,
 * the top card says each step, with "Stop", as "Equipment · mount · centring". The top bar's
 * menu ([menuOpen]) holds the tracking rate, unpark and home, as "Equipment · mount · more".
 */
@Composable
fun MountScreen(
    mount: MountMediator,
    centring: Centring,
    menuOpen: Boolean = false,
    onMenuClose: () -> Unit = {},
    onPolarAlignment: () -> Unit = {},
) {
    val live by mount.state.collectAsState()
    val centringState by centring.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<Exception?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<CoordinatesAction?>(null) }
    val rates = rememberMoveRates(mount)
    var rate by rememberSaveable { mutableStateOf(DEFAULT_RATE) }

    fun act(block: suspend () -> Unit) = scope.act({
        failure = null
        notice = null
        centring.dismiss()
        block()
    }) { failure = it }

    val state = live.value
    val parkHint = stringResource(Res.string.mount_park_hint)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            val working = centringState as? CentringState.Working
            if (working == null) {
                Text(state?.let { mountTitle(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.centring_title, working.attempt),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SettingChip(stringResource(Res.string.centring_stop), onClick = centring::stop)
                }
                Text(
                    centringStepText(working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ReadingGrid(
                listOf(
                    stringResource(Res.string.readout_ra) to (state?.let { formatHms(it.rightAscensionHours) } ?: "—"),
                    stringResource(Res.string.readout_dec) to (state?.let { formatDms(it.declinationDeg) } ?: "—"),
                    stringResource(Res.string.readout_alt) to (state?.let { formatDm(it.altitudeDeg) } ?: "—"),
                    stringResource(Res.string.readout_az) to (state?.let { formatDm(it.azimuthDeg) } ?: "—"),
                ),
                Modifier.padding(top = 6.dp),
            )
            val problem =
                when (val error = failure) {
                    is BelowHorizonException ->
                        stringResource(Res.string.mount_below_horizon, formatDm(error.target.altitudeDeg), formatDm(error.limitDeg))

                    null -> live.error ?: notice

                    else -> error.message ?: error::class.simpleName.orEmpty()
                }
            problem?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it == notice) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            CentringResult(centringState)
        }

        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                DirectionPad(
                    onMove = { axis, sign -> act { mount.move(axis, sign * rate) } },
                    onRelease = { axis -> act { mount.move(axis, 0.0) } },
                    onStop = { act { mount.stop() } },
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rates.forEach { (name, value) -> SettingChip(name, onClick = { rate = value }, selected = rate == value) }
                }
                Text(
                    stringResource(Res.string.mount_move_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SurfaceButton(stringResource(Res.string.mount_go_to_button), {
                    dialog = CoordinatesAction.GoTo
                }, Modifier.weight(1f), YacanaIcons.Target)
                if (mount.capabilities.canSync) {
                    SurfaceButton(stringResource(Res.string.mount_sync), {
                        dialog = CoordinatesAction.Sync
                    }, Modifier.weight(1f), YacanaIcons.Crosshair)
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state?.atPark == true && mount.capabilities.canUnpark) {
                    // Parked, the same place unparks, with a tap: nothing moves that could hit anything.
                    SurfaceButton(
                        stringResource(Res.string.mount_unpark),
                        { act { mount.unpark() } },
                        Modifier.weight(1f),
                        YacanaIcons.Unpark,
                    )
                } else if (mount.capabilities.canPark) {
                    HoldButton(
                        text = stringResource(Res.string.mount_park),
                        hint = null,
                        onConfirm = { act { mount.park() } },
                        modifier = Modifier.weight(1f),
                        enabled = state?.atPark != true,
                        icon = YacanaIcons.Park,
                        onShortTap = { notice = parkHint },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (mount.capabilities.canSetTracking) {
                    val tracking = state?.tracking == true
                    SurfaceButton(
                        stringResource(Res.string.mount_tracking_switch),
                        { act { mount.setTracking(!tracking) } },
                        Modifier.weight(1f),
                        if (tracking) YacanaIcons.ToggleOn else YacanaIcons.ToggleOff,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            SurfaceButton(stringResource(Res.string.mount_polar_alignment), onPolarAlignment, Modifier.fillMaxWidth(), YacanaIcons.Compass)
        }
    }

    dialog?.let { action ->
        BottomSheet(onDismiss = { dialog = null }) {
            val options = centring.options
            val centreHint =
                stringResource(
                    Res.string.mount_centre_hint,
                    formatDecimal(options.centringExposureSeconds, 0, decimalComma()),
                    formatOffset(options.centringToleranceArcsec),
                )
            CoordinatesEntry(action, state?.rightAscensionHours, state?.declinationDeg, centreHint) { ra, dec, centre ->
                dialog = null
                if (centre) {
                    failure = null
                    notice = null
                    centring.centreMountCoordinates(ra, dec)
                } else {
                    act { if (action == CoordinatesAction.GoTo) mount.goTo(ra, dec) else mount.sync(ra, dec) }
                }
            }
        }
    }
    if (menuOpen) {
        BottomSheet(onDismiss = onMenuClose) {
            Text(stringResource(Res.string.role_mount), style = MaterialTheme.typography.titleMedium)
            if (mount.capabilities.trackingRates.size > 1) {
                Text(
                    stringResource(Res.string.mount_tracking_rate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mount.capabilities.trackingRates.forEach { driveRate ->
                        SettingChip(
                            stringResource(driveRate.title),
                            onClick = { act { mount.setTrackingRate(driveRate) } },
                            selected =
                            state?.trackingRate == driveRate,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mount.capabilities.canUnpark) {
                    SurfaceButton(stringResource(Res.string.mount_unpark), {
                        onMenuClose()
                        act { mount.unpark() }
                    }, Modifier.weight(1f), YacanaIcons.Unpark, enabled = state?.atPark == true)
                }
                if (mount.capabilities.canFindHome) {
                    SurfaceButton(stringResource(Res.string.mount_find_home), {
                        onMenuClose()
                        act { mount.findHome() }
                    }, Modifier.weight(1f), YacanaIcons.Home)
                }
            }
        }
    }
}

private enum class CoordinatesAction { GoTo, Sync }

private val DriveRate.title: StringResource
    get() =
        when (this) {
            DriveRate.Sidereal -> Res.string.rate_sidereal
            DriveRate.Lunar -> Res.string.rate_lunar
            DriveRate.Solar -> Res.string.rate_solar
            DriveRate.King -> Res.string.rate_king
        }

/**
 * The move rates, as the designs: 1×, 8× and 64× the sidereal rate, and the mount's maximum once
 * it says it; each with its name.
 */
@Composable
internal fun rememberMoveRates(mount: MountMediator): List<Pair<String, Double>> {
    var maxRate by remember(mount) { mutableStateOf<Double?>(null) }
    LaunchedEffect(mount) {
        maxRate = runCatchingDevice { mount.telescope.axisRates(TelescopeAxis.Primary).maxOfOrNull { it.endInclusive } }
    }
    val max = stringResource(Res.string.mount_rate_max)
    return RATES.map { "$it×" to it * SIDEREAL } + listOfNotNull(maxRate?.let { max to it })
}

/** 8× the sidereal rate, as the designs mark it. */
internal val DEFAULT_RATE = RATES[1] * SIDEREAL

/**
 * The design's pad: north and south move the declination axis, east and west the right ascension
 * axis, as in N.I.N.A.'s manual controls. Each arrow moves while it is held and stops when let go;
 * the middle stops everything. [key] is each one's size: smaller on the sky map, where they go
 * without their letters.
 */
@Composable
internal fun DirectionPad(
    onMove: (TelescopeAxis, Double) -> Unit,
    onRelease: (TelescopeAxis) -> Unit,
    onStop: () -> Unit,
    key: Dp = PAD_KEY,
) {
    val letters = key == PAD_KEY
    val gap = if (letters) 8.dp else 4.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        HoldToMove(YacanaIcons.ArrowUp, stringResource(Res.string.mount_direction_north), key, letters, {
            onMove(TelescopeAxis.Secondary, 1.0)
        }) { onRelease(TelescopeAxis.Secondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            // West on the left and east on the right, as on a map.
            HoldToMove(YacanaIcons.ArrowLeft, stringResource(Res.string.mount_direction_west), key, letters, {
                onMove(TelescopeAxis.Primary, -1.0)
            }) { onRelease(TelescopeAxis.Primary) }
            val stop = stringResource(Res.string.mount_stop)
            Box(
                Modifier
                    .size(key)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.error, CircleShape)
                    .clickable(onClickLabel = stop, role = Role.Button, onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    YacanaIcons.Stop,
                    contentDescription = stop,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(key * ICON_SHARE),
                )
            }
            HoldToMove(YacanaIcons.ArrowRight, stringResource(Res.string.mount_direction_east), key, letters, {
                onMove(TelescopeAxis.Primary, 1.0)
            }) { onRelease(TelescopeAxis.Primary) }
        }
        HoldToMove(YacanaIcons.ArrowDown, stringResource(Res.string.mount_direction_south), key, letters, {
            onMove(TelescopeAxis.Secondary, -1.0)
        }) { onRelease(TelescopeAxis.Secondary) }
    }
}

@Composable
@Suppress("LongParameterList") // An arrow: what it shows, its size, and what holding it does.
private fun HoldToMove(icon: ImageVector, label: String, key: Dp, letters: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    // The gesture outlives a composition: it moves as the latest one says, at the rate chosen since.
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Column(
        Modifier
            .size(key)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    press()
                    waitForUpOrCancellation()
                    release()
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(if (letters) 28.dp else key * ICON_SHARE))
        if (letters) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Right ascension and declination on the app's keypad, as "Equipment · mount · go to": the marked
 * box takes the digits, and fills from the left, hours or degrees first; ± changes the
 * declination's sign. Each box shows the mount's position until its first key.
 */
@Composable
private fun CoordinatesEntry(
    action: CoordinatesAction,
    raHours: Double?,
    decDegrees: Double?,
    centreHint: String,
    onConfirm: (ra: Double, dec: Double, centre: Boolean) -> Unit,
) {
    var ra by remember { mutableStateOf(raHours?.let { sexagesimalDigits(it) } ?: "") }
    var dec by remember { mutableStateOf(decDegrees?.let { sexagesimalDigits(abs(it)) } ?: "") }
    var south by remember { mutableStateOf((decDegrees ?: 0.0) < 0) }
    var onDec by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(setOf(0, 1)) }
    val raValue = sexagesimal(ra, max = HOURS)
    val decValue = sexagesimal(dec, max = RIGHT_ANGLE)?.let { if (south) -it else it }
    val title = stringResource(if (action == CoordinatesAction.GoTo) Res.string.mount_go_to else Res.string.mount_sync)

    fun type(key: Char) {
        // The sign is not a digit: it keeps the box's first digit replacing what it shows.
        if (key == '±') {
            south = !south
            onDec = true
            return
        }
        val field = if (onDec) 1 else 0
        val current = if (field in fresh) {
            ""
        } else if (onDec) {
            dec
        } else {
            ra
        }
        fresh = fresh - field
        if (onDec) {
            dec = (current + key).take(DIGITS)
        } else {
            ra = (current + key).take(DIGITS)
            if (ra.length == DIGITS) onDec = true
        }
    }

    Text(title, style = MaterialTheme.typography.titleMedium)
    CoordinateBox(stringResource(Res.string.readout_ra), formatDigits(ra, "h", "m", "s"), !onDec, raValue != null) { onDec = false }
    CoordinateBox(
        stringResource(Res.string.readout_dec),
        (if (south) "−" else "+") + formatDigits(dec, "°", "′", "″"),
        onDec,
        decValue != null,
    ) {
        onDec = true
    }
    Text(
        stringResource(Res.string.mount_coordinates_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NumberPad(
        onKey = ::type,
        onDelete = {
            val field = if (onDec) 1 else 0
            if (field in fresh) {
                fresh = fresh - field
                if (onDec) dec = "" else ra = ""
            } else if (onDec) {
                dec = dec.dropLast(1)
            } else {
                ra = ra.dropLast(1)
            }
        },
        deleteLabel = stringResource(Res.string.number_delete),
        decimal = '±',
    )
    val valid = raValue != null && decValue != null
    fun confirm(centre: Boolean) {
        if (raValue != null && decValue != null) onConfirm(raValue, decValue, centre)
    }
    if (action == CoordinatesAction.Sync) {
        AccentButton(onClick = { confirm(centre = false) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Icon(YacanaIcons.Crosshair, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(title, modifier = Modifier.padding(start = 6.dp))
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceButton(title, { confirm(centre = false) }, Modifier.weight(1f), YacanaIcons.Target, enabled = valid)
        AccentButton(onClick = { confirm(centre = true) }, enabled = valid, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
            Icon(YacanaIcons.Crosshair, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.mount_go_and_centre), modifier = Modifier.padding(start = 6.dp))
        }
    }
    Text(centreHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CoordinateBox(label: String, value: String, selected: Boolean, valid: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = if (valid) colors.onSurface else colors.error,
            modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.5.dp, if (selected) colors.primary else colors.outline, RoundedCornerShape(10.dp))
                .clickable(onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

/** Six digits, hours or degrees, minutes and seconds, for [value]. */
private fun sexagesimalDigits(value: Double): String {
    val seconds = (value * SECONDS_PER_UNIT).roundToLong()
    val whole = seconds / SECONDS_PER_UNIT
    val minutes = seconds % SECONDS_PER_UNIT / MINUTE
    return listOf(whole, minutes, seconds % MINUTE).joinToString("") { it.toString().padStart(2, '0') }
}

/** Typed digits, filled with zeros on the right, as `00h 42m 44s`. */
private fun formatDigits(digits: String, vararg units: String): String {
    val full = digits.padEnd(DIGITS, '0')
    return units.indices.joinToString(" ") { full.substring(it * 2, it * 2 + 2) + units[it] }
}

/** The value of typed digits, or null if a field is out of range or it passes [max]. */
private fun sexagesimal(digits: String, max: Double): Double? {
    val full = digits.padEnd(DIGITS, '0')
    val whole = full.substring(0, 2).toInt()
    val minutes = full.substring(2, 4).toInt()
    val seconds = full.substring(4, 6).toInt()
    if (minutes >= MINUTE || seconds >= MINUTE) return null
    val value = whole + minutes / MINUTE.toDouble() + seconds / SECONDS_PER_UNIT.toDouble()
    return value.takeIf { if (max == HOURS) it < max else it <= max }
}

/** Runs a device command; a failure goes to [onError], so it is shown and nothing fails in silence. */
@Suppress("TooGenericExceptionCaught") // Whatever the device answers, the user sees it.
internal fun CoroutineScope.act(block: suspend () -> Unit, onError: (Exception) -> Unit) {
    launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // An optional read: a failure only hides what it would show.
internal suspend fun <T> runCatchingDevice(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private val PAD_KEY = 76.dp

/** How much of an arrow its icon takes. */
private const val ICON_SHARE = 0.4f
private const val DIGITS = 6
private const val MINUTE = 60
private const val SECONDS_PER_UNIT = 3600L
private const val HOURS = 24.0
private const val RIGHT_ANGLE = 90.0
