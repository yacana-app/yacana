// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.Profile
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.eq_connect
import app.yacana.ui.resources.eq_connected
import app.yacana.ui.resources.eq_connecting
import app.yacana.ui.resources.eq_disconnect_all
import app.yacana.ui.resources.eq_disconnected
import app.yacana.ui.resources.eq_failed
import app.yacana.ui.resources.eq_find_hint
import app.yacana.ui.resources.eq_find_title
import app.yacana.ui.resources.eq_not_set_up
import app.yacana.ui.resources.eq_profile
import app.yacana.ui.resources.eq_profile_open
import app.yacana.ui.resources.eq_profiles
import app.yacana.ui.resources.eq_profiles_disconnect_first
import app.yacana.ui.resources.eq_site_none
import app.yacana.ui.resources.eq_site_phone
import app.yacana.ui.resources.eq_site_typed
import app.yacana.ui.resources.profile_import
import org.jetbrains.compose.resources.stringResource

/**
 * The Equipment section, as its design, "Equipment · devices": the
 * profile in use, which opens the list of profiles; the connection; and a row per device with
 * its live state, which opens its screen; under it, "Not set up", the devices the profile
 * could add, which open "Add device".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EquipmentScreen(controller: EquipmentController, open: (Screen) -> Unit) {
    val state by controller.state.collectAsState()
    val profile = state.active
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (profile == null) {
            // Without a profile, as "First steps · your equipment", with the import of a shared profile under it.
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.eq_find_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(Res.string.eq_find_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FindEquipment(controller, onUsed = {
                controller.connect()
            })
            OutlinedButton(onClick = { open(Screen.Profile) }, modifier = Modifier.padding(horizontal = 14.dp)) {
                Text(stringResource(Res.string.profile_import))
            }
        } else {
            ProfileCard(profile) { choosing = true }
            ConnectionRow(profile, state.connection, controller)
            SectionCard {
                val roles = DeviceRole.entries.filter { it in profile.devices.byRole() }
                roles.forEachIndexed { index, role ->
                    ProfileDeviceRow(role, profile, state.connection, last = index == roles.lastIndex) { open(Screen.Device(role)) }
                }
            }
            val missing = OPTIONAL.filter { it !in profile.devices.byRole() }
            if (missing.isNotEmpty()) {
                SectionCard {
                    Text(
                        stringResource(Res.string.eq_not_set_up),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        missing.forEach { role -> SettingChip(stringResource(role.title), { open(Screen.AddEquipment) }) }
                    }
                }
            }
        }
    }
    if (choosing && profile != null) {
        BottomSheet(onDismiss = { choosing = false }) {
            ProfileChoice(
                controller,
                profile,
                state.connection is Connection.Disconnected || state.connection is Connection.Failed,
            ) { screen ->
                choosing = false
                screen?.let(open)
            }
        }
    }
}

/** The design's profile card: "Profile", the profile's name with a chevron to choose another, and where it is. */
@Composable
private fun ProfileCard(profile: Profile, onChoose: () -> Unit) {
    val site = profile.location?.let { formatSite(it) }
    val siteLine =
        when {
            site == null -> stringResource(Res.string.eq_site_none)
            profile.options.locationFromPhone -> stringResource(Res.string.eq_site_phone, site)
            else -> stringResource(Res.string.eq_site_typed, site)
        }
    SectionCard(onClick = onChoose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.eq_profile),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
            }
            Icon(YacanaIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(siteLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The design's line under the profile: how many devices are connected, and the chip to connect or disconnect them. */
@Composable
private fun ConnectionRow(profile: Profile, connection: Connection, controller: EquipmentController) {
    val total = profile.devices.byRole().size
    val (text, color) =
        when (connection) {
            Connection.Disconnected -> stringResource(Res.string.eq_disconnected) to MaterialTheme.colorScheme.onSurfaceVariant

            Connection.Connecting -> stringResource(Res.string.eq_connecting) to MaterialTheme.colorScheme.onSurface

            is Connection.Failed -> stringResource(Res.string.eq_failed, connection.reason) to MaterialTheme.colorScheme.error

            is Connection.Connected -> {
                val failed = connection.equipment.failures.size
                stringResource(Res.string.eq_connected, total - failed, total) to
                    if (failed == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            }
        }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YacanaIcons.PlugConnected, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
        if (connection is Connection.Connected || connection == Connection.Connecting) {
            SettingChip(stringResource(Res.string.eq_disconnect_all), onClick = controller::disconnect)
        } else {
            SettingChip(stringResource(Res.string.eq_connect), onClick = controller::connect, selected = true)
        }
    }
}

/** The profile card's sheet: the profiles to choose from, and the profile's own screen. */
@Composable
private fun ProfileChoice(controller: EquipmentController, active: Profile, canChange: Boolean, onDone: (Screen?) -> Unit) {
    val state by controller.state.collectAsState()
    Text(stringResource(Res.string.eq_profiles), style = MaterialTheme.typography.titleMedium)
    if (!canChange) {
        Text(
            stringResource(Res.string.eq_profiles_disconnect_first),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        state.profiles.forEachIndexed { index, profile ->
            DeviceRow(
                title = profile.name,
                supporting = null,
                last = index == state.profiles.lastIndex,
                onClick =
                if (canChange) {
                    {
                        controller.activate(profile.id)
                        onDone(null)
                    }
                } else {
                    null
                },
            ) {
                if (profile.id == active.id) Icon(YacanaIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    OutlinedButton(onClick = { onDone(Screen.Profile) }) { Text(stringResource(Res.string.eq_profile_open)) }
}

/** The devices a profile may have or not, offered under the list while it has none. */
private val OPTIONAL = listOf(DeviceRole.FlatPanel, DeviceRole.SafetyMonitor)
