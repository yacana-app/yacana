// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.solving

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.SolverOptions
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.domain.solver.STAR_DATABASES
import app.yacana.domain.solver.StarDatabase
import app.yacana.domain.solver.StarDatabases
import app.yacana.domain.solver.fieldHeightDeg
import app.yacana.domain.solver.recommendedDatabase
import app.yacana.ui.FilePicker
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatOffset
import app.yacana.ui.format.formatSize
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.image_files_no_profile
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import app.yacana.ui.resources.solver_astap
import app.yacana.ui.resources.solver_astap_summary
import app.yacana.ui.resources.solver_cancel
import app.yacana.ui.resources.solver_centring_exposure
import app.yacana.ui.resources.solver_centring_exposure_hint
import app.yacana.ui.resources.solver_centring_tolerance
import app.yacana.ui.resources.solver_centring_tolerance_hint
import app.yacana.ui.resources.solver_database
import app.yacana.ui.resources.solver_databases_hint
import app.yacana.ui.resources.solver_delete
import app.yacana.ui.resources.solver_delete_frees
import app.yacana.ui.resources.solver_delete_hint
import app.yacana.ui.resources.solver_delete_in_use
import app.yacana.ui.resources.solver_download
import app.yacana.ui.resources.solver_downsample
import app.yacana.ui.resources.solver_downsample_auto
import app.yacana.ui.resources.solver_downsample_hint
import app.yacana.ui.resources.solver_downsample_value
import app.yacana.ui.resources.solver_free
import app.yacana.ui.resources.solver_import
import app.yacana.ui.resources.solver_in_use
import app.yacana.ui.resources.solver_radius
import app.yacana.ui.resources.solver_radius_hint
import app.yacana.ui.resources.solver_recommended
import app.yacana.ui.resources.solver_seconds
import app.yacana.ui.resources.solver_timeout
import app.yacana.ui.resources.solver_timeout_hint
import app.yacana.ui.resources.solver_try_last
import app.yacana.ui.resources.solver_use
import app.yacana.ui.resources.solver_your_field
import org.jetbrains.compose.resources.stringResource

/** Which option a sheet edits. */
private enum class SolverOption { Downsample, Radius, Timeout, Tolerance, Exposure }

/**
 * ASTAP's options and the star database of the profile in use, as "Settings ·
 * plate solving": the databases to use, download or import below the options, and a solve of the
 * last frame to try them.
 */
@Composable
fun PlateSolvingScreen(
    equipment: EquipmentController,
    solving: PlateSolving,
    centring: Centring,
    databases: StarDatabases,
    files: FilePicker,
) {
    val state by equipment.state.collectAsState()
    val solve by solving.state.collectAsState()
    val profile = state.active
    var editing by remember { mutableStateOf<SolverOption?>(null) }
    var showSolve by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val comma = decimalComma()
    if (profile == null) {
        Text(stringResource(Res.string.image_files_no_profile), color = muted, modifier = Modifier.padding(20.dp))
        return
    }
    val options = profile.options.solver
    fun save(changed: SolverOptions) = equipment.save(profile.copy(options = profile.options.copy(solver = changed)))
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            DeviceRow(
                stringResource(Res.string.solver_astap),
                stringResource(Res.string.solver_astap_summary),
                YacanaIcons.Crosshair,
                last = true,
            ) {
            }
            OptionRow(stringResource(Res.string.solver_downsample), downsampleText(options.downsample)) {
                editing = SolverOption.Downsample
            }
            OptionRow(stringResource(Res.string.solver_radius), "${formatDecimal(options.searchRadiusDeg, 0, comma)}°") {
                editing =
                    SolverOption.Radius
            }
            OptionRow(stringResource(Res.string.solver_timeout), stringResource(Res.string.solver_seconds, options.timeoutSeconds)) {
                editing = SolverOption.Timeout
            }
            OptionRow(stringResource(Res.string.solver_centring_tolerance), formatOffset(options.centringToleranceArcsec)) {
                editing = SolverOption.Tolerance
            }
            OptionRow(
                stringResource(Res.string.solver_centring_exposure),
                "${formatDecimal(options.centringExposureSeconds, 0, comma)} s",
            ) { editing = SolverOption.Exposure }
        }
        DatabasesCard(equipment, databases, files)
        SurfaceButton(
            stringResource(Res.string.solver_try_last),
            onClick = {
                solving.solveLastFrame()
                showSolve = true
            },
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        )
    }
    if (showSolve) {
        BottomSheet(onDismiss = {
            showSolve = false
            solving.dismiss()
        }) { SolveSheet(solve, centring) }
    }
    editing?.let { option ->
        BottomSheet(onDismiss = { editing = null }) {
            OptionSheet(option, options) { changed ->
                editing = null
                save(changed)
            }
        }
    }
}

@Composable
private fun downsampleText(factor: Int): String =
    if (factor == 0) stringResource(Res.string.solver_downsample_auto) else stringResource(Res.string.solver_downsample_value, factor)

/** An option as the design draws it: its name, and its value on the right. */
@Composable
private fun OptionRow(label: String, value: String, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One option on chips or the app's keypad, with what it does. */
@Composable
private fun OptionSheet(option: SolverOption, options: SolverOptions, onSave: (SolverOptions) -> Unit) {
    val comma = decimalComma()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (option) {
        SolverOption.Downsample -> {
            Text(stringResource(Res.string.solver_downsample), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.solver_downsample_hint), style = MaterialTheme.typography.bodyMedium, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (factor in 0..MAX_DOWNSAMPLE) {
                    SettingChip(
                        downsampleText(factor),
                        onClick = { onSave(options.copy(downsample = factor)) },
                        selected =
                        options.downsample == factor,
                    )
                }
            }
        }

        SolverOption.Radius -> {
            Text(stringResource(Res.string.solver_radius), style = MaterialTheme.typography.titleMedium)
            NumberEntry(
                initial = formatDecimal(options.searchRadiusDeg, 0, comma),
                unit = "°",
                hint = stringResource(Res.string.solver_radius_hint),
                saveLabel = stringResource(Res.string.save),
                deleteLabel = stringResource(Res.string.number_delete),
                decimal = null,
                valid = { it in 1.0..MAX_RADIUS },
            ) { onSave(options.copy(searchRadiusDeg = it)) }
        }

        SolverOption.Timeout -> {
            Text(stringResource(Res.string.solver_timeout), style = MaterialTheme.typography.titleMedium)
            NumberEntry(
                initial = options.timeoutSeconds.toString(),
                unit = "s",
                hint = stringResource(Res.string.solver_timeout_hint),
                saveLabel = stringResource(Res.string.save),
                deleteLabel = stringResource(Res.string.number_delete),
                decimal = null,
                valid = { it in MIN_TIMEOUT..MAX_TIMEOUT },
            ) { onSave(options.copy(timeoutSeconds = it.toInt())) }
        }

        SolverOption.Tolerance -> {
            Text(stringResource(Res.string.solver_centring_tolerance), style = MaterialTheme.typography.titleMedium)
            NumberEntry(
                initial = formatDecimal(options.centringToleranceArcsec, 0, comma),
                unit = "″",
                hint = stringResource(Res.string.solver_centring_tolerance_hint),
                saveLabel = stringResource(Res.string.save),
                deleteLabel = stringResource(Res.string.number_delete),
                decimal = null,
                valid = { it in MIN_TOLERANCE..MAX_TOLERANCE },
            ) { onSave(options.copy(centringToleranceArcsec = it)) }
        }

        SolverOption.Exposure -> {
            Text(stringResource(Res.string.solver_centring_exposure), style = MaterialTheme.typography.titleMedium)
            NumberEntry(
                initial = formatDecimal(options.centringExposureSeconds, 0, comma),
                unit = "s",
                hint = stringResource(Res.string.solver_centring_exposure_hint),
                saveLabel = stringResource(Res.string.save),
                deleteLabel = stringResource(Res.string.number_delete),
                decimal = null,
                valid = { it in MIN_EXPOSURE..MAX_EXPOSURE },
            ) { onSave(options.copy(centringExposureSeconds = it)) }
        }
    }
}

private const val MAX_DOWNSAMPLE = 4
private const val MAX_RADIUS = 180.0
private const val MIN_TIMEOUT = 5.0
private const val MAX_TIMEOUT = 600.0
private const val MIN_TOLERANCE = 5.0
private const val MAX_TOLERANCE = 600.0
private const val MIN_EXPOSURE = 1.0
private const val MAX_EXPOSURE = 60.0

/**
 * The star databases, as "Settings · plate solving (lower)": each with its size and fields, the one
 * recommended for the connected camera's field, and "Use", "Download" or "Cancel"; then the
 * free space and "Import file".
 */
@Composable
private fun DatabasesCard(equipment: EquipmentController, databases: StarDatabases, files: FilePicker) {
    val state by databases.state.collectAsState()
    val equipmentState by equipment.state.collectAsState()
    val profile = equipmentState.active ?: return
    val field = fieldHeightDeg(equipmentState)
    val recommended = recommendedDatabase(field)
    val comma = decimalComma()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    var deleting by remember { mutableStateOf<StarDatabase?>(null) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.solver_database),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            field?.let {
                Text(
                    stringResource(Res.string.solver_your_field, "${formatDecimal(it, 2, comma)}°"),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
        val tag = stringResource(Res.string.solver_recommended)
        STAR_DATABASES.forEach { database ->
            val name = database.name
            val arrival = state.files.arriving[name]
            val onPhone = name in state.files.installed
            val inUse = onPhone && profile.options.starDatabase == name
            val title =
                buildAnnotatedString {
                    append(name)
                    if (database == recommended) withStyle(SpanStyle(color = accent)) { append(" · $tag") }
                }
            val supporting = arrival?.let { arrivalText(it) } ?: "${formatSize(database.downloadBytes, comma)} · ${fieldsText(database)}"
            DeviceRow(
                title,
                supporting,
                YacanaIcons.Database,
                trailing = {
                    when {
                        arrival != null -> SettingChip(stringResource(Res.string.solver_cancel), onClick = { databases.cancel(name) })
                        inUse -> InUse()
                        onPhone -> SettingChip(stringResource(Res.string.solver_use), onClick = { databases.use(name) })
                        else -> SettingChip(stringResource(Res.string.solver_download), onClick = { databases.download(database) })
                    }
                },
                onLongClick = if (onPhone && arrival == null) ({ deleting = database }) else null,
            )
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.solver_free, formatSize(state.files.freeBytes, comma)),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            SettingChip(stringResource(Res.string.solver_import), onClick = {
                files.openFile(databases::import)
            }, icon = YacanaIcons.FileImport)
        }
        DatabaseProblemText(state, Modifier.padding(bottom = 6.dp))
        Text(
            stringResource(Res.string.solver_databases_hint),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    deleting?.let { database ->
        DeleteSheet(database, inUse = profile.options.starDatabase == database.name, onDelete = { databases.delete(database.name) }) {
            deleting = null
        }
    }
}

/** What deleting [database] frees, and the button to hold for it, as deleting a profile; not the one in use. */
@Composable
private fun DeleteSheet(database: StarDatabase, inUse: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    BottomSheet(onDismiss = onDismiss) {
        Text(database.name, style = MaterialTheme.typography.titleMedium)
        Text(
            if (inUse) {
                stringResource(Res.string.solver_delete_in_use)
            } else {
                stringResource(Res.string.solver_delete_frees, formatSize(database.installedBytes, decimalComma()))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HoldButton(
            text = stringResource(Res.string.solver_delete),
            hint = if (inUse) null else stringResource(Res.string.solver_delete_hint),
            onConfirm = {
                onDelete()
                onDismiss()
            },
            enabled = !inUse,
            icon = YacanaIcons.Trash,
            danger = true,
        )
    }
}

/** The database in use, as the design marks it: a dot in the accent colour. */
@Composable
private fun InUse() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(YacanaIcons.CircleDot, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(
            stringResource(Res.string.solver_in_use),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
