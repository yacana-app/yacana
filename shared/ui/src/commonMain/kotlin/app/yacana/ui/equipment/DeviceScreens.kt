// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.GainControl
import app.yacana.alpaca.SwitchInfo
import app.yacana.domain.equipment.CameraMediator
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.FilterWheelMediator
import app.yacana.domain.equipment.FocuserMediator
import app.yacana.domain.equipment.SwitchesMediator
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.solver.Centring
import app.yacana.ui.Section
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.ReadingGrid
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.StatusKind
import app.yacana.ui.design.StatusLine
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.focus.AutofocusButton
import app.yacana.ui.focus.FilterOffsets
import app.yacana.ui.focus.LastAutofocusLine
import app.yacana.ui.focus.LastCurve
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.camera_bayer
import app.yacana.ui.resources.camera_exposure_range
import app.yacana.ui.resources.camera_exposure_up_to
import app.yacana.ui.resources.camera_gain
import app.yacana.ui.resources.camera_go_imaging
import app.yacana.ui.resources.camera_none
import app.yacana.ui.resources.camera_offset
import app.yacana.ui.resources.camera_pixel
import app.yacana.ui.resources.camera_sensor
import app.yacana.ui.resources.camera_temperature
import app.yacana.ui.resources.camera_where_capture
import app.yacana.ui.resources.device_not_connected
import app.yacana.ui.resources.focuser_go_to
import app.yacana.ui.resources.focuser_go_to_title
import app.yacana.ui.resources.focuser_move
import app.yacana.ui.resources.focuser_position_label
import app.yacana.ui.resources.focuser_range
import app.yacana.ui.resources.mount_stop
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import app.yacana.ui.resources.switch_range
import app.yacana.ui.resources.switch_read_only
import app.yacana.ui.resources.switch_state_off
import app.yacana.ui.resources.switch_state_on
import app.yacana.ui.resources.wheel_hint
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource

/** The screen of one connected device, as its design in "Equipment". */
@Composable
fun DeviceScreen(
    role: DeviceRole,
    controller: EquipmentController,
    centring: Centring,
    autofocus: Autofocus,
    zone: () -> TimeZone,
    open: (Screen) -> Unit = {},
    menuOpen: Boolean = false,
    onMenuClose: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val equipment = (state.connection as? Connection.Connected)?.equipment
    val mount = equipment?.mount
    val camera = equipment?.camera
    val focuser = equipment?.focuser
    val wheel = equipment?.filterWheel
    val switches = equipment?.switches
    val panel = equipment?.flatPanel
    val monitor = equipment?.safetyMonitor
    when {
        role == DeviceRole.Mount && mount != null -> MountScreen(mount, centring, menuOpen, onMenuClose) { open(Screen.PolarAlignment) }

        role == DeviceRole.Camera && camera != null -> CameraScreen(camera) { open(Screen.Root(Section.Imaging)) }

        role == DeviceRole.Focuser && focuser != null ->
            FocuserScreen(focuser, autofocus, zone, offsets = { wheel?.let { FilterOffsets(controller, it.names) } }) {
                open(Screen.Autofocus)
            }

        role == DeviceRole.FilterWheel && wheel != null -> FilterWheelScreen(wheel)

        role == DeviceRole.Switches && switches != null -> SwitchesScreen(switches, state.active?.name.orEmpty())

        role == DeviceRole.FlatPanel && panel != null -> FlatPanelScreen(panel, state.active?.devices?.flatPanel?.name.orEmpty())

        role == DeviceRole.SafetyMonitor && monitor != null -> {
            val live by monitor.safe.collectAsState()
            SafetyMonitorContent(live.value, state.active?.devices?.safetyMonitor?.name.orEmpty(), live.error)
        }

        else -> StatusLine(stringResource(Res.string.device_not_connected), StatusKind.Off, Modifier.padding(16.dp))
    }
}

/**
 * Every switch as it declares itself, as "Equipment · switches": on and off with a
 * switch, a value with a slider and −/+ within its range and step, or a reading.
 */
@Composable
private fun SwitchesScreen(switches: SwitchesMediator, owner: String) {
    val live by switches.values.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Unit) = scope.act(block) { failure = it.message }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Text(owner, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (live.error ?: failure)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            switches.switches.forEachIndexed { index, info ->
                val value = live.value?.getOrNull(index)
                val last = index == switches.switches.lastIndex
                when {
                    !info.canWrite -> DeviceRow(info.name, stringResource(Res.string.switch_read_only), switchIcon(info), last = last) {
                        Text(value?.let { formatSwitchValue(info, it) } ?: "—", style = MaterialTheme.typography.titleMedium)
                    }

                    info.isOnOff -> {
                        val on = value != null && value >= 1.0
                        DeviceRow(
                            info.name,
                            stringResource(if (on) Res.string.switch_state_on else Res.string.switch_state_off),
                            switchIcon(info),
                            last = last,
                        ) {
                            Switch(checked = on, onCheckedChange = { act { switches.setOn(info.id, it) } })
                        }
                    }

                    else -> SwitchValue(info, value, last) { act { switches.setValue(info.id, it) } }
                }
            }
        }
    }
}

/**
 * A switch that takes a value, as the design's heater at 60 %: the slider follows the finger, and
 * the device gets the value when it is let go.
 */
@Composable
private fun SwitchValue(info: SwitchInfo, value: Double?, last: Boolean, onSet: (Double) -> Unit) {
    var dragged by remember(value) { mutableFloatStateOf((value ?: info.min).toFloat()) }
    val step = if (info.step > 0) info.step else 1.0
    fun set(next: Double) {
        val clamped = next.coerceIn(info.min, info.max)
        dragged = clamped.toFloat()
        onSet(clamped)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(switchIcon(info), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(info.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(formatSwitchValue(info, dragged.toDouble()), style = MaterialTheme.typography.titleMedium)
        }
        val steps = if (info.step > 0) ((info.max - info.min) / info.step).toInt() - 1 else 0
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onSet(dragged.toDouble()) },
            valueRange = info.min.toFloat()..info.max.toFloat(),
            steps = steps.coerceIn(0, MAX_SLIDER_STEPS),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { set(dragged - step) }) { Icon(YacanaIcons.Minus, contentDescription = null) }
            IconButton(onClick = { set(dragged + step) }) { Icon(YacanaIcons.Add, contentDescription = null) }
            Text(
                stringResource(
                    Res.string.switch_range,
                    formatSwitchValue(info, info.min),
                    formatSwitchValue(info, info.max),
                    formatDecimal(step, 0, decimalComma()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** The design's icons: a flame for heaters, a bolt for readings, a plug for other outputs. */
private fun switchIcon(info: SwitchInfo): ImageVector {
    val name = (info.name + " " + info.description).lowercase()
    return when {
        HEATER_WORDS.any { it in name } -> YacanaIcons.Flame
        !info.canWrite -> YacanaIcons.Bolt
        else -> YacanaIcons.PlugConnected
    }
}

private val HEATER_WORDS = listOf("heater", "dew", "calentador", "antivaho", "rocío")

/**
 * The focuser, as "Equipment · focuser": its position and temperature, moves in steps, and a
 * position typed on the app's keypad; stop while it moves.
 */
@Composable
private fun FocuserScreen(
    focuser: FocuserMediator,
    autofocus: Autofocus,
    zone: () -> TimeZone,
    offsets: @Composable () -> Unit,
    onAutofocus: () -> Unit,
) {
    val live by focuser.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) = scope.act(block) { failure = it.message }
    val status = live.value
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.focuser_position_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(status?.position?.toString() ?: "—", style = MaterialTheme.typography.displaySmall)
                }
                status?.temperature?.let { temperature ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(Res.string.camera_temperature),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("${formatTemperature(temperature)} °C", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            LastAutofocusLine(autofocus, zone)
            (live.error ?: failure)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        SectionCard {
            Text(
                stringResource(Res.string.focuser_move),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val position = status?.position
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-100, -10, 10, 100).forEach { step ->
                    SurfaceButton(
                        if (step > 0) "+$step" else "−${-step}",
                        { if (position != null) act { focuser.moveTo(position + step) } },
                        Modifier.weight(1f),
                        enabled = position != null,
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingChip(stringResource(Res.string.focuser_go_to), onClick = { typing = true })
                if (status?.moving == true) SettingChip(stringResource(Res.string.mount_stop), onClick = { act { focuser.halt() } })
            }
        }
        LastCurve(autofocus)
        offsets()
        AutofocusButton(onAutofocus)
    }
    if (typing) {
        BottomSheet(onDismiss = { typing = false }) {
            Text(stringResource(Res.string.focuser_go_to_title), style = MaterialTheme.typography.titleMedium)
            NumberEntry(
                initial = status?.position?.toString().orEmpty(),
                unit = "",
                hint = stringResource(Res.string.focuser_range, focuser.info.maxStep),
                saveLabel = stringResource(Res.string.save),
                deleteLabel = stringResource(Res.string.number_delete),
                decimal = null,
                valid = { it >= 0 && it <= focuser.info.maxStep },
            ) { target ->
                typing = false
                act { focuser.moveTo(target.toInt()) }
            }
        }
    }
}

/** The filters by name, as "Equipment · filter wheel"; tapping one selects it. */
@Composable
private fun FilterWheelScreen(wheel: FilterWheelMediator) {
    val live by wheel.position.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            (live.error ?: failure)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            wheel.names.forEachIndexed { index, name ->
                val selected = live.value == index
                DeviceRow(
                    title = name,
                    supporting = null,
                    last = index == wheel.names.lastIndex,
                    onClick = { scope.act({ wheel.select(index) }) { failure = it.message } },
                ) {
                    if (selected) Icon(YacanaIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(
            stringResource(Res.string.wheel_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** What the camera is, as "Equipment · camera"; frames are taken in Imaging. */
@Composable
private fun CameraScreen(camera: CameraMediator, onImaging: () -> Unit) {
    val live by camera.state.collectAsState()
    val info = camera.info
    val comma = decimalComma()
    val none = stringResource(Res.string.camera_none)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Text(
                listOfNotNull(
                    live.value?.let { stringResource(cameraStateTitle(it.state)) },
                    live.value?.ccdTemperature?.let { "${formatTemperature(it)} °C" },
                ).joinToString(" · ").ifEmpty { "—" },
                style = MaterialTheme.typography.titleMedium,
            )
            ReadingGrid(
                listOf(
                    stringResource(Res.string.camera_sensor) to "${info.width} × ${info.height}",
                    stringResource(Res.string.camera_pixel) to "${formatDecimal(info.pixelSizeX, 2, comma)} µm",
                    stringResource(Res.string.camera_bayer) to (info.bayerPattern ?: info.sensorType.name),
                    stringResource(Res.string.camera_gain) to (describe(info.gain) ?: none),
                    stringResource(Res.string.camera_exposure_range) to
                        stringResource(Res.string.camera_exposure_up_to, formatDecimal(info.exposureMax, 0, comma)),
                    stringResource(Res.string.camera_offset) to (describe(info.offset) ?: none),
                ),
                Modifier.padding(top = 6.dp),
            )
            live.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        Text(
            stringResource(Res.string.camera_where_capture),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        AccentButton(onClick = onImaging, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Icon(YacanaIcons.Imaging, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.camera_go_imaging), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

private fun describe(control: GainControl): String? = when (control) {
    is GainControl.Range -> "${control.min}–${control.max}"
    is GainControl.Named -> control.names.joinToString(" · ")
    GainControl.None -> null
}

private const val MAX_SLIDER_STEPS = 100
