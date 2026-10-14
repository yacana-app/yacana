// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentState
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileFormatException
import app.yacana.domain.profile.parseHorizon
import app.yacana.domain.solver.STAR_DATABASES
import app.yacana.domain.solver.StarDatabases
import app.yacana.domain.solver.fieldHeightDeg
import app.yacana.domain.solver.recommendedDatabase
import app.yacana.ui.FilePicker
import app.yacana.ui.PermissionsController
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatSize
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.profile_add_equipment
import app.yacana.ui.resources.profile_altitude_limit
import app.yacana.ui.resources.profile_altitude_limit_summary
import app.yacana.ui.resources.profile_copy
import app.yacana.ui.resources.profile_copy_summary
import app.yacana.ui.resources.profile_database
import app.yacana.ui.resources.profile_database_missing
import app.yacana.ui.resources.profile_database_pending
import app.yacana.ui.resources.profile_database_recommended
import app.yacana.ui.resources.profile_database_summary
import app.yacana.ui.resources.profile_delete
import app.yacana.ui.resources.profile_delete_hint
import app.yacana.ui.resources.profile_disconnect_first
import app.yacana.ui.resources.profile_export_short
import app.yacana.ui.resources.profile_horizon
import app.yacana.ui.resources.profile_horizon_clear
import app.yacana.ui.resources.profile_horizon_flat
import app.yacana.ui.resources.profile_horizon_hint
import app.yacana.ui.resources.profile_horizon_import
import app.yacana.ui.resources.profile_horizon_points
import app.yacana.ui.resources.profile_import
import app.yacana.ui.resources.profile_import_failed
import app.yacana.ui.resources.profile_import_short
import app.yacana.ui.resources.profile_limits
import app.yacana.ui.resources.profile_name
import app.yacana.ui.resources.profile_new
import app.yacana.ui.resources.profile_none
import app.yacana.ui.resources.profile_others
import app.yacana.ui.resources.profile_use
import app.yacana.ui.resources.solver_cancel
import app.yacana.ui.resources.solver_download
import app.yacana.ui.solving.DatabaseProblemText
import app.yacana.ui.solving.arrivalText
import org.jetbrains.compose.resources.stringResource

/**
 * The profile in use, as "Settings · profile and location": its name; its
 * location, from the phone or typed in; its limits and horizon; its star database; a copy in a
 * file; the other profiles and a new one; and delete, held. Every change is saved at once.
 */
@Composable
@Suppress("LongParameterList") // The profile, its files and the location's permission.
fun ProfileScreen(
    controller: EquipmentController,
    databases: StarDatabases,
    files: FilePicker,
    permissions: PermissionsController,
    open: (Screen) -> Unit,
    close: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val profile = state.active
    var error by remember { mutableStateOf<String?>(null) }
    fun importing(block: () -> Unit) {
        error =
            try {
                block()
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: ProfileFormatException) {
                e.message
            }
    }
    if (profile == null) {
        // A new phone starts here: find the equipment, or import a profile exported from another phone.
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.profile_none), style = MaterialTheme.typography.bodyLarge)
            error?.let { Text(stringResource(Res.string.profile_import_failed, it), color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { open(Screen.NewProfile) }) { Text(stringResource(Res.string.profile_add_equipment)) }
            OutlinedButton(onClick = { files.open { text -> importing { controller.import(text) } } }) {
                Text(stringResource(Res.string.profile_import))
            }
        }
        return
    }
    val connected = state.connection !is Connection.Disconnected && state.connection !is Connection.Failed
    fun save(changed: Profile) = controller.save(changed)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOfNotNull(
            stringResource(Res.string.profile_disconnect_first).takeIf { connected },
            error?.let { stringResource(Res.string.profile_import_failed, it) },
        ).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        SectionCard {
            Muted(stringResource(Res.string.profile_name))
            NameField(profile.name) { if (it.isNotBlank()) save(profile.copy(name = it)) }
        }
        OpticsCard(profile, ::save)
        LocationCard(profile, permissions, controller::refreshPhoneLocation, ::save)
        LimitsCard(profile, files, ::save) { text ->
            importing { save(profile.copy(options = profile.options.copy(horizon = parseHorizon(text)))) }
        }

        DatabaseCard(profile, databases, state)

        SectionCard {
            Muted(stringResource(Res.string.profile_copy))
            Text(
                stringResource(Res.string.profile_copy_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SurfaceButton(
                    stringResource(Res.string.profile_export_short),
                    { controller.exportActive()?.let { files.save("${profile.name}.json", it) } },
                    Modifier.weight(1f),
                    YacanaIcons.Diagnostics,
                )
                SurfaceButton(
                    stringResource(Res.string.profile_import_short),
                    { files.open { text -> importing { controller.import(text) } } },
                    Modifier.weight(1f),
                    YacanaIcons.FileImport,
                    enabled = !connected,
                )
            }
        }

        OtherProfiles(controller, profile, connected, open)

        run {
            HoldButton(
                text = stringResource(Res.string.profile_delete),
                hint = stringResource(Res.string.profile_delete_hint),
                onConfirm = {
                    controller.delete(profile.id)
                    close()
                },
                modifier = Modifier.padding(horizontal = 14.dp),
                enabled = !connected,
                icon = YacanaIcons.Trash,
                danger = true,
            )
        }
    }
}

/**
 * The profile's star database, as "Settings · profile and location (lower)": a chip for each. One on
 * the phone is used at once. For one that is not, its size and "Download" show below; it is used
 * when its download ends, and until then the profile keeps the one it had.
 */
@Composable
private fun DatabaseCard(profile: Profile, databases: StarDatabases, equipment: EquipmentState) {
    val state by databases.state.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    val comma = decimalComma()
    val inUse = profile.options.starDatabase
    val files = state.files
    SectionCard {
        Muted(stringResource(Res.string.profile_database))
        FlowRow(
            Modifier.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            STAR_DATABASES.forEach { database ->
                SettingChip(database.name, onClick = {
                    picked = database.name
                    databases.use(database.name)
                }, selected = inUse == database.name)
            }
        }
        // The one tapped, else one arriving for this profile, else the profile's own if it is not on the phone.
        val shown =
            picked?.takeIf { it != inUse || it !in files.installed }
                ?: files.arriving.entries.firstOrNull { it.value.owner == profile.id }?.key
                ?: inUse.takeIf { it !in files.installed }
        STAR_DATABASES.firstOrNull { it.name == shown }?.let { database ->
            val arrival = files.arriving[database.name]
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    arrival?.let { "${database.name} · ${arrivalText(it)}" }
                        ?: stringResource(Res.string.profile_database_missing, database.name, formatSize(database.downloadBytes, comma)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (arrival != null) {
                    SettingChip(stringResource(Res.string.solver_cancel), onClick = { databases.cancel(database.name) })
                } else {
                    SettingChip(stringResource(Res.string.solver_download), onClick = { databases.download(database) })
                }
            }
            if (database.name != inUse) Muted(stringResource(Res.string.profile_database_pending, inUse))
        }
        DatabaseProblemText(state)
        val field = fieldHeightDeg(equipment)
        Muted(
            if (field == null) {
                stringResource(Res.string.profile_database_summary)
            } else {
                stringResource(
                    Res.string.profile_database_recommended,
                    "${formatDecimal(field, 2, comma)}°",
                    recommendedDatabase(field).name,
                )
            },
        )
    }
}

/** A small label above a card's content, as the designs' muted headings. */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The design's name box: a pill on the raised surface with a pencil, edited in place. */
@Composable
private fun NameField(name: String, onChange: (String) -> Unit) {
    var text by remember(name) { mutableStateOf(name) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .heightIn(min = Touch.Main)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.weight(1f),
        )
        Icon(YacanaIcons.Pencil, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** The design's limits: the altitude limit with −/+, and the horizon with its import. */
@Composable
private fun LimitsCard(profile: Profile, files: FilePicker, save: (Profile) -> Unit, onHorizon: (String) -> Unit) {
    val limit = profile.options.minAltitudeDeg.toInt()
    fun setLimit(value: Int) =
        save(profile.copy(options = profile.options.copy(minAltitudeDeg = value.coerceIn(0, MAX_ALTITUDE_LIMIT).toDouble())))
    SectionCard {
        Muted(stringResource(Res.string.profile_limits))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.profile_altitude_limit),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            RoundButton(enabled = limit > 0, onClick = { setLimit(limit - 1) }) { Icon(YacanaIcons.Minus, contentDescription = null) }
            Text("$limit°", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
            RoundButton(enabled = limit < MAX_ALTITUDE_LIMIT, onClick = {
                setLimit(limit + 1)
            }) { Icon(YacanaIcons.Add, contentDescription = null) }
        }
        Muted(stringResource(Res.string.profile_altitude_limit_summary))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 10.dp))
        val horizon = profile.options.horizon
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.profile_horizon), style = MaterialTheme.typography.bodyLarge)
                Muted(
                    if (horizon.isEmpty()) {
                        stringResource(
                            Res.string.profile_horizon_flat,
                        )
                    } else {
                        stringResource(Res.string.profile_horizon_points, horizon.size)
                    },
                )
            }
            if (horizon.isNotEmpty()) {
                SettingChip(stringResource(Res.string.profile_horizon_clear), onClick = {
                    save(profile.copy(options = profile.options.copy(horizon = emptyList())))
                })
            }
            SettingChip(stringResource(Res.string.profile_horizon_import), onClick = { files.open(onHorizon) })
        }
        Text(
            stringResource(Res.string.profile_horizon_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A round −/+ button on the raised surface, as the design kit's `sp` steppers. */
@Composable
internal fun RoundButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        IconButton(onClick = onClick, enabled = enabled) { content() }
    }
}

/** The design's other profiles, each with "Use", and a new one from equipment found on the WiFi. */
@Composable
private fun OtherProfiles(controller: EquipmentController, active: Profile, connected: Boolean, open: (Screen) -> Unit) {
    val state by controller.state.collectAsState()
    val others = state.profiles.filter { it.id != active.id }
    SectionCard {
        Muted(stringResource(Res.string.profile_others))
        others.forEach { profile ->
            DeviceRow(profile.name, supporting = null, icon = YacanaIcons.Equipment) {
                if (!connected) SettingChip(stringResource(Res.string.profile_use), onClick = { controller.activate(profile.id) })
            }
        }
        val color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !connected) { open(Screen.NewProfile) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(YacanaIcons.Add, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.profile_new), style = MaterialTheme.typography.bodyLarge, color = color)
        }
    }
}

private const val MAX_ALTITUDE_LIMIT = 60
