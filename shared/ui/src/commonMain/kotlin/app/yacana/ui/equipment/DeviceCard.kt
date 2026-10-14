// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import app.yacana.alpaca.CameraState
import app.yacana.alpaca.DriveRate
import app.yacana.domain.equipment.ConnectedEquipment
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.Live
import app.yacana.domain.equipment.MountState
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.Profile
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.RowChevron
import app.yacana.ui.format.formatDm
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.camera_downloading
import app.yacana.ui.resources.camera_error
import app.yacana.ui.resources.camera_exposing
import app.yacana.ui.resources.camera_idle
import app.yacana.ui.resources.camera_reading
import app.yacana.ui.resources.camera_waiting
import app.yacana.ui.resources.device_failed
import app.yacana.ui.resources.eq_disconnected
import app.yacana.ui.resources.focuser_moving
import app.yacana.ui.resources.live_no_answer
import app.yacana.ui.resources.live_reading
import app.yacana.ui.resources.mount_parked
import app.yacana.ui.resources.mount_slewing
import app.yacana.ui.resources.mount_status_stopped
import app.yacana.ui.resources.mount_status_tracking
import app.yacana.ui.resources.rate_status_king
import app.yacana.ui.resources.rate_status_lunar
import app.yacana.ui.resources.rate_status_sidereal
import app.yacana.ui.resources.rate_status_solar
import app.yacana.ui.resources.role_camera
import app.yacana.ui.resources.role_connected_camera
import app.yacana.ui.resources.role_connected_filter_wheel
import app.yacana.ui.resources.role_connected_flat_panel
import app.yacana.ui.resources.role_connected_focuser
import app.yacana.ui.resources.role_connected_mount
import app.yacana.ui.resources.role_connected_safety_monitor
import app.yacana.ui.resources.role_connected_switches
import app.yacana.ui.resources.role_filter_wheel
import app.yacana.ui.resources.role_flat_panel
import app.yacana.ui.resources.role_focuser
import app.yacana.ui.resources.role_mount
import app.yacana.ui.resources.role_safety_monitor
import app.yacana.ui.resources.role_switches
import app.yacana.ui.resources.safety_monitor_safe
import app.yacana.ui.resources.safety_monitor_unsafe
import app.yacana.ui.resources.status_line
import app.yacana.ui.resources.switch_off
import app.yacana.ui.resources.switch_on
import app.yacana.ui.resources.wheel_moving
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One device of the profile, as the design's rows in "Equipment · devices": its icon, its role,
 * and its live state in a line, such as "Connected · tracking · alt 52° 10′". Tapping it
 * opens its screen.
 */
@Composable
fun ProfileDeviceRow(role: DeviceRole, profile: Profile, connection: Connection, last: Boolean, onClick: () -> Unit) {
    val equipment = (connection as? Connection.Connected)?.equipment
    val failure = equipment?.failures?.get(role)
    val usable = equipment != null && failure == null
    val (line, error) =
        when {
            equipment == null -> stringResource(Res.string.eq_disconnected) to false
            failure != null -> stringResource(Res.string.device_failed, failure) to true
            else -> liveLine(role, equipment)
        }
    val subtitle = profile.devices.byRole().getValue(role).name.takeIf { equipment == null && it.isNotBlank() }
    DeviceRow(
        title = stringResource(role.title),
        supporting = subtitle?.let { "$it · $line" } ?: line,
        icon = role.icon,
        supportingColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        last = last,
        onClick = if (usable) onClick else null,
    ) { if (usable) RowChevron() }
}

val DeviceRole.title: StringResource
    get() =
        when (this) {
            DeviceRole.Mount -> Res.string.role_mount
            DeviceRole.Camera -> Res.string.role_camera
            DeviceRole.Focuser -> Res.string.role_focuser
            DeviceRole.FilterWheel -> Res.string.role_filter_wheel
            DeviceRole.Switches -> Res.string.role_switches
            DeviceRole.FlatPanel -> Res.string.role_flat_panel
            DeviceRole.SafetyMonitor -> Res.string.role_safety_monitor
        }

/** The design's icon for each device. */
val DeviceRole.icon: ImageVector
    get() =
        when (this) {
            DeviceRole.Mount -> YacanaIcons.Equipment
            DeviceRole.Camera -> YacanaIcons.Camera
            DeviceRole.Focuser -> YacanaIcons.Focuser
            DeviceRole.FilterWheel -> YacanaIcons.Filter
            DeviceRole.Switches -> YacanaIcons.ToggleOn
            DeviceRole.FlatPanel -> YacanaIcons.Bulb
            DeviceRole.SafetyMonitor -> YacanaIcons.ShieldCheck
        }

/** "Connected" agreeing with the device's name, which in Spanish depends on its gender. */
private val DeviceRole.connected: StringResource
    get() =
        when (this) {
            DeviceRole.Mount -> Res.string.role_connected_mount
            DeviceRole.Camera -> Res.string.role_connected_camera
            DeviceRole.Focuser -> Res.string.role_connected_focuser
            DeviceRole.FilterWheel -> Res.string.role_connected_filter_wheel
            DeviceRole.Switches -> Res.string.role_connected_switches
            DeviceRole.FlatPanel -> Res.string.role_connected_flat_panel
            DeviceRole.SafetyMonitor -> Res.string.role_connected_safety_monitor
        }

/** The device's state after "Connected"; the second value says whether it is an error. */
@Composable
private fun liveLine(role: DeviceRole, equipment: ConnectedEquipment): Pair<String, Boolean> {
    val state: Pair<String, Boolean>? =
        when (role) {
            DeviceRole.Mount -> equipment.mount?.let { mount ->
                val live by mount.state.collectAsState()
                live(live) { mountStatus(it) }
            }

            DeviceRole.Camera -> equipment.camera?.let { camera ->
                val live by camera.state.collectAsState()
                live(live) { status ->
                    listOfNotNull(
                        stringResource(cameraStateTitle(status.state)),
                        status.ccdTemperature?.let {
                            "${formatTemperature(it)} °C"
                        },
                    )
                        .joinToString(" · ")
                }
            }

            DeviceRole.Focuser -> equipment.focuser?.let { focuser ->
                val live by focuser.state.collectAsState()
                live(live) { status ->
                    if (status.moving) {
                        stringResource(Res.string.focuser_moving)
                    } else {
                        listOfNotNull(status.position?.toString() ?: "—", status.temperature?.let { "${formatTemperature(it)} °C" })
                            .joinToString(" · ")
                    }
                }
            }

            DeviceRole.FilterWheel -> equipment.filterWheel?.let { wheel ->
                val live by wheel.position.collectAsState()
                live(live) { position -> position?.let { wheel.names.getOrNull(it) } ?: stringResource(Res.string.wheel_moving) }
            }

            DeviceRole.Switches -> equipment.switches?.let { switches ->
                val live by switches.values.collectAsState()
                val on = stringResource(Res.string.switch_on)
                val off = stringResource(Res.string.switch_off)
                val comma = decimalComma()
                live(live) { values ->
                    switches.switches.zip(values).joinToString(" · ") { (info, value) ->
                        val shown = if (info.isOnOff) (if (value >= 1.0) on else off) else formatSwitchValue(info, value, comma)
                        "${info.name} $shown"
                    }
                }
            }

            DeviceRole.FlatPanel -> equipment.flatPanel?.let { panel ->
                val live by panel.state.collectAsState()
                live(live) { flatPanelLine(it, panel.maxBrightness) }
            }

            DeviceRole.SafetyMonitor -> equipment.safetyMonitor?.let { monitor ->
                val live by monitor.safe.collectAsState()
                live(live) { stringResource(if (it) Res.string.safety_monitor_safe else Res.string.safety_monitor_unsafe) }
            }
        }
    val (text, error) = state ?: return stringResource(Res.string.eq_disconnected) to false
    // The app's own words follow "Connected" in lower case; names the device gives, such as filters, stay as they are.
    val shown = if (role == DeviceRole.FilterWheel || role == DeviceRole.Switches) text else text.lowercaseStart()
    return stringResource(Res.string.status_line, stringResource(role.connected), shown) to error
}

/** The text with its first letter in lower case, after "Connected ·"; acronyms such as "IR" stay as they are. */
private fun String.lowercaseStart(): String = if (length > 1 && this[1].isUpperCase()) this else replaceFirstChar { it.lowercase() }

/** The latest value described, or why there is none: still reading, or the device's last error. */
@Composable
private fun <T> live(live: Live<T>, describe: @Composable (T) -> String): Pair<String, Boolean> {
    val value = live.value
    val error = live.error
    return when {
        error != null -> stringResource(Res.string.live_no_answer, error) to true
        value == null -> stringResource(Res.string.live_reading) to false
        else -> describe(value) to false
    }
}

@Composable
private fun mountStatus(state: MountState): String = when {
    state.atPark -> stringResource(Res.string.mount_parked)
    state.slewing -> stringResource(Res.string.mount_slewing)
    state.tracking -> stringResource(Res.string.mount_status_tracking, formatDm(state.altitudeDeg).let { "alt $it" })
    else -> "${stringResource(Res.string.mount_status_stopped)} · alt ${formatDm(state.altitudeDeg)}"
}

/** The mount's state for its screen's title, as the design's "Tracking · sidereal". */
@Composable
fun mountTitle(state: MountState): String = when {
    state.atPark -> stringResource(Res.string.mount_parked)
    state.slewing -> stringResource(Res.string.mount_slewing)
    state.tracking -> stringResource(Res.string.mount_status_tracking, stringResource(state.trackingRate.status))
    else -> stringResource(Res.string.mount_status_stopped)
}

private val DriveRate?.status: StringResource
    get() =
        when (this) {
            DriveRate.Lunar -> Res.string.rate_status_lunar
            DriveRate.Solar -> Res.string.rate_status_solar
            DriveRate.King -> Res.string.rate_status_king
            DriveRate.Sidereal, null -> Res.string.rate_status_sidereal
        }

internal fun cameraStateTitle(state: CameraState): StringResource = when (state) {
    CameraState.Idle -> Res.string.camera_idle
    CameraState.Waiting -> Res.string.camera_waiting
    CameraState.Exposing -> Res.string.camera_exposing
    CameraState.Reading -> Res.string.camera_reading
    CameraState.Download -> Res.string.camera_downloading
    CameraState.Error -> Res.string.camera_error
}
