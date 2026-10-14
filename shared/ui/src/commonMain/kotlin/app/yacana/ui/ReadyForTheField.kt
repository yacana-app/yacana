// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.domain.sky.SkyCacheException
import app.yacana.domain.solver.fieldHeightDeg
import app.yacana.domain.solver.recommendedDatabase
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatSize
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.field_cache
import app.yacana.ui.resources.field_cache_hint
import app.yacana.ui.resources.field_cache_installed
import app.yacana.ui.resources.field_catalogue
import app.yacana.ui.resources.field_catalogue_hint
import app.yacana.ui.resources.field_database
import app.yacana.ui.resources.field_database_hint
import app.yacana.ui.resources.field_database_installed
import app.yacana.ui.resources.field_database_later
import app.yacana.ui.resources.field_ephemerides
import app.yacana.ui.resources.field_ephemerides_hint
import app.yacana.ui.resources.field_import
import app.yacana.ui.resources.field_no_signal
import app.yacana.ui.resources.solver_cancel
import app.yacana.ui.resources.solver_download
import app.yacana.ui.solving.arrivalText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * "First steps · offline": what the night needs and where it comes from.
 * The catalogue is inside the app and the ephemerides are computed on the phone; the star database
 * for the equipment's field downloads with WiFi, here or later; and NINA's sky cache is imported
 * from a ZIP. In the field the phone only talks to the equipment.
 */
@Composable
internal fun ReadyForTheField(services: AppServices) {
    val comma = decimalComma()
    SectionCard {
        Step(
            YacanaIcons.CircleCheck,
            MaterialTheme.colorScheme.tertiary,
            stringResource(Res.string.field_catalogue),
            stringResource(Res.string.field_catalogue_hint),
        )
        Step(
            YacanaIcons.CircleCheck,
            MaterialTheme.colorScheme.tertiary,
            stringResource(Res.string.field_ephemerides),
            stringResource(Res.string.field_ephemerides_hint),
        )
        StarDatabaseStep(services, comma)
        SkyCacheStep(services)
    }
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(YacanaIcons.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(stringResource(Res.string.field_no_signal), modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** The star database for the equipment's field: installed, arriving with its progress, or "Download". */
@Composable
private fun StarDatabaseStep(services: AppServices, comma: Boolean) {
    val state by services.starDatabases.state.collectAsState()
    val equipment by services.equipment.state.collectAsState()
    val database = recommendedDatabase(fieldHeightDeg(equipment))
    val title = stringResource(Res.string.field_database, database.name)
    val arrival = state.files.arriving[database.name]
    when {
        database.name in state.files.installed -> Step(
            YacanaIcons.CircleCheck,
            MaterialTheme.colorScheme.tertiary,
            title,
            stringResource(Res.string.field_database_installed, formatSize(database.installedBytes, comma)),
        )

        arrival != null -> Step(YacanaIcons.Download, MaterialTheme.colorScheme.primary, title, arrivalText(arrival)) {
            SettingChip(stringResource(Res.string.solver_cancel), { services.starDatabases.cancel(database.name) })
        }

        // A database belongs to a profile: without equipment yet, it comes later.
        equipment.active == null -> Step(YacanaIcons.Circle, null, title, stringResource(Res.string.field_database_later))

        else -> Step(
            YacanaIcons.Circle,
            null,
            title,
            stringResource(Res.string.field_database_hint, formatSize(database.downloadBytes, comma)),
        ) {
            SettingChip(stringResource(Res.string.solver_download), { services.starDatabases.download(database) })
        }
    }
}

/** NINA's sky cache: installed, arriving, or "Import" from a ZIP. */
@Composable
private fun SkyCacheStep(services: AppServices) {
    val store = services.skyCaches ?: return
    val files by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val title = stringResource(Res.string.field_cache)
    val installed = files.installed.firstOrNull()
    val arrival = files.arriving.values.firstOrNull()
    when {
        installed != null -> Step(
            YacanaIcons.CircleCheck,
            MaterialTheme.colorScheme.tertiary,
            title,
            stringResource(Res.string.field_cache_installed, installed.name),
            last = true,
        )

        arrival != null -> Step(YacanaIcons.Download, MaterialTheme.colorScheme.primary, title, arrivalText(arrival), last = true)

        else -> Step(YacanaIcons.Circle, null, title, problem ?: stringResource(Res.string.field_cache_hint), last = true) {
            SettingChip(stringResource(Res.string.field_import), {
                services.files.openFile { location ->
                    problem = null
                    scope.launch {
                        try {
                            store.import(location, folder = false)
                        } catch (e: SkyCacheException) {
                            problem = e.message
                        }
                    }
                }
            }, icon = YacanaIcons.FileImport)
        }
    }
}

/** One thing the night needs, as the design's rows: its mark, its name over where it comes from, and its button. */
@Composable
private fun Step(
    icon: ImageVector,
    tint: Color?,
    title: String,
    status: String,
    last: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Touch.Min).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint ?: colors.onSurfaceVariant, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            action?.invoke()
        }
        if (!last) HorizontalDivider(color = colors.outline)
    }
}
