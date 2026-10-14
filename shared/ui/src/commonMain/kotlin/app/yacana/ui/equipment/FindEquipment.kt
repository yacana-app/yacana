// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.AlpacaServer
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.ProfilePresets
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.eq_add
import app.yacana.ui.resources.eq_add_by_address
import app.yacana.ui.resources.eq_add_device
import app.yacana.ui.resources.eq_address_label
import app.yacana.ui.resources.eq_all_in_profile
import app.yacana.ui.resources.eq_devices
import app.yacana.ui.resources.eq_disconnect_to_add
import app.yacana.ui.resources.eq_found
import app.yacana.ui.resources.eq_none_found
import app.yacana.ui.resources.eq_search_again
import app.yacana.ui.resources.eq_search_failed
import app.yacana.ui.resources.eq_searching
import app.yacana.ui.resources.eq_seestar_profile
import app.yacana.ui.resources.eq_seestar_profiles
import app.yacana.ui.resources.eq_use
import app.yacana.ui.resources.eq_use_telephoto
import app.yacana.ui.resources.eq_use_wide_angle
import app.yacana.ui.resources.eq_wide_angle_profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Finds the Alpaca equipment on the WiFi, or a server by its address. With [onUsed], as
 * "First steps · your equipment": the Seestar stands out with its own profiles, for its telephoto
 * or its wide-angle camera, and each other server becomes the profile with a tap; [onUsed] follows
 * once the new profile is the active one. Without it, as "Equipment · add device": each device
 * the active profile lacks joins it with a tap.
 */
@Composable
fun FindEquipment(controller: EquipmentController, onUsed: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf(emptyList<AlpacaServer>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }

    @Suppress("TooGenericExceptionCaught") // Any failure is shown to the user.
    fun attempt(block: suspend () -> List<AlpacaServer>) {
        scope.launch {
            searching = true
            error = null
            try {
                servers = (servers + block()).distinctBy { it.url }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            } finally {
                searching = false
            }
        }
    }
    LaunchedEffect(Unit) { attempt { controller.discover() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchLine(searching, error, servers.size) { attempt { controller.discover() } }
        servers.forEach { server ->
            if (onUsed != null) UseCard(server, controller, onUsed) else AddCard(server, controller)
        }
        if (typing) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(Res.string.eq_address_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                AccentButton(onClick = { attempt { listOf(controller.server(address)) } }, enabled = address.isNotBlank() && !searching) {
                    Text(stringResource(Res.string.eq_add))
                }
            }
        } else {
            Row(
                Modifier.clickable { typing = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(YacanaIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(Res.string.eq_add_by_address),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The design's line above the servers: searching, what was found, or why it failed, with a chip to search again. */
@Composable
private fun SearchLine(searching: Boolean, error: String?, found: Int, onSearch: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val (text, color) =
            when {
                searching -> stringResource(Res.string.eq_searching) to colors.onSurfaceVariant
                error != null -> stringResource(Res.string.eq_search_failed, error) to colors.error
                found == 0 -> stringResource(Res.string.eq_none_found) to colors.onSurfaceVariant
                else -> stringResource(Res.string.eq_found, found) to colors.onSurfaceVariant
            }
        if (searching) Icon(YacanaIcons.Loader, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        if (!searching) SettingChip(stringResource(Res.string.eq_search_again), onClick = onSearch)
    }
}

/**
 * A server to use as the profile: the Seestar as the design's highlighted card, with a button for
 * its telephoto and one for its wide-angle camera, any other as a row with "Add".
 */
@Composable
private fun UseCard(server: AlpacaServer, controller: EquipmentController, onUsed: () -> Unit) {
    val onUse = {
        controller.use(server)
        onUsed()
    }
    val seestar = ProfilePresets.isSeestarS50Pro(server)
    val host = server.url.removePrefix("http://")
    if (seestar) {
        SectionCard(highlighted = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    YacanaIcons.Equipment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Column {
                    Text(SEESTAR, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$host · ${pluralStringResource(Res.plurals.eq_devices, server.devices.size, server.devices.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                Modifier.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                server.devices.mapNotNull { DeviceRole.of(it.type) }.distinct().forEach { role ->
                    SettingChip(stringResource(role.title), onClick = {})
                }
            }
            val wideAngle = ProfilePresets.hasWideAngle(server)
            if (wideAngle) {
                val name = stringResource(Res.string.eq_wide_angle_profile)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton(onClick = onUse, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
                        Text(stringResource(Res.string.eq_use_telephoto))
                    }
                    SurfaceButton(stringResource(Res.string.eq_use_wide_angle), onClick = {
                        controller.useWideAngle(server, name)
                        onUsed()
                    }, modifier = Modifier.weight(1f))
                }
            } else {
                AccentButton(onClick = onUse, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.eq_use)) }
            }
            Text(
                stringResource(if (wideAngle) Res.string.eq_seestar_profiles else Res.string.eq_seestar_profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    } else {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(YacanaIcons.Router, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.description.serverName.ifBlank { host }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "$host · ${pluralStringResource(Res.plurals.eq_devices, server.devices.size, server.devices.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingChip(stringResource(Res.string.eq_add_device), onClick = onUse)
            }
        }
    }
}

/** A server whose devices can join the active profile, each with "Add", or a line saying they are all there already. */
@Composable
private fun AddCard(server: AlpacaServer, controller: EquipmentController) {
    val state by controller.state.collectAsState()
    val profile = state.active ?: return
    val inProfile = profile.devices.byRole().values
    val usable = server.devices.filter { DeviceRole.of(it.type) != null }
    val missing = usable.filter { device -> inProfile.none { it.uniqueId == device.uniqueId && it.number == device.number } }
    val connected = state.connection is Connection.Connected || state.connection == Connection.Connecting
    val seestar = ProfilePresets.isSeestarS50Pro(server)
    val host = server.url.removePrefix("http://")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (seestar) YacanaIcons.Equipment else YacanaIcons.Router, contentDescription = null, modifier = Modifier.size(28.dp))
            Column {
                Text(if (seestar) SEESTAR else server.description.serverName.ifBlank { host }, style = MaterialTheme.typography.titleMedium)
                Text(host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            missing.isEmpty() -> Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(YacanaIcons.Check, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.eq_all_in_profile, usable.size), style = MaterialTheme.typography.bodyMedium, color = muted)
            }

            connected -> Text(
                stringResource(Res.string.eq_disconnect_to_add),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(top = 8.dp),
            )

            else -> Column(Modifier.padding(top = 4.dp)) {
                missing.forEachIndexed { index, device ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(DeviceRole.of(device.type)!!.title), style = MaterialTheme.typography.bodyLarge)
                            Text(device.name, style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        SettingChip(stringResource(Res.string.eq_add_device), onClick = { controller.add(server, device) })
                    }
                    if (index != missing.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

private const val SEESTAR = "Seestar S50 Pro"
