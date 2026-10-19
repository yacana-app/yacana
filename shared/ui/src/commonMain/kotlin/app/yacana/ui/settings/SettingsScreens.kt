// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.AutofocusOptions
import app.yacana.domain.profile.DEFAULT_GAIN
import app.yacana.domain.sky.NINA_CACHES
import app.yacana.domain.sky.SkyCacheFiles
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.ui.LogExport
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.RowChevron
import app.yacana.ui.design.SearchField
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.Touch
import app.yacana.ui.focus.fittingName
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.about
import app.yacana.ui.resources.about_summary
import app.yacana.ui.resources.af_backlash_in
import app.yacana.ui.resources.af_backlash_out
import app.yacana.ui.resources.af_fitting
import app.yacana.ui.resources.af_points
import app.yacana.ui.resources.af_settings_summary
import app.yacana.ui.resources.af_step
import app.yacana.ui.resources.autofocus
import app.yacana.ui.resources.camera_settings
import app.yacana.ui.resources.camera_settings_summary
import app.yacana.ui.resources.display
import app.yacana.ui.resources.display_summary
import app.yacana.ui.resources.image_files
import app.yacana.ui.resources.image_files_summary
import app.yacana.ui.resources.imaging_gain
import app.yacana.ui.resources.language
import app.yacana.ui.resources.language_system
import app.yacana.ui.resources.log_export
import app.yacana.ui.resources.log_export_summary
import app.yacana.ui.resources.permissions
import app.yacana.ui.resources.permissions_summary
import app.yacana.ui.resources.plate_solving
import app.yacana.ui.resources.plate_solving_summary
import app.yacana.ui.resources.profile_database
import app.yacana.ui.resources.safety
import app.yacana.ui.resources.safety_battery
import app.yacana.ui.resources.safety_lost
import app.yacana.ui.resources.safety_stop
import app.yacana.ui.resources.safety_summary
import app.yacana.ui.resources.settings_nothing_found
import app.yacana.ui.resources.settings_profile
import app.yacana.ui.resources.settings_profile_none
import app.yacana.ui.resources.settings_profile_phone
import app.yacana.ui.resources.settings_profile_typed
import app.yacana.ui.resources.settings_search
import app.yacana.ui.resources.sky_caches
import app.yacana.ui.resources.sky_import
import app.yacana.ui.resources.sky_layers
import app.yacana.ui.resources.sky_settings
import app.yacana.ui.resources.sky_settings_none
import app.yacana.ui.resources.solver_centring_exposure
import app.yacana.ui.resources.solver_centring_tolerance
import app.yacana.ui.resources.solver_database
import app.yacana.ui.resources.solver_downsample
import app.yacana.ui.resources.solver_radius
import app.yacana.ui.resources.solver_timeout
import app.yacana.ui.sky.title
import org.jetbrains.compose.resources.stringResource

/**
 * Settings, from the gear, as "Settings": a search box, then one card with an entry
 * each, with what it holds. The design's mount and alerts entries arrive with their features.
 */
@Composable
fun SettingsScreen(
    open: (Screen) -> Unit,
    equipment: EquipmentController,
    skyCaches: SkyCacheStore? = null,
    languages: LanguageSettings = SystemLanguage(),
    logExport: LogExport? = null,
) {
    val state by equipment.state.collectAsState()
    val chosen by languages.chosen.collectAsState()
    val caches = skyCaches?.state?.collectAsState()?.value
    val profile = state.active
    val profileSummary =
        when {
            profile == null -> stringResource(Res.string.settings_profile_none)

            profile.location != null && !profile.options.locationFromPhone -> stringResource(
                Res.string.settings_profile_typed,
                profile.name,
            )

            else -> stringResource(Res.string.settings_profile_phone, profile.name)
        }
    val autofocus = profile?.options?.autofocus ?: AutofocusOptions()
    val entries =
        listOf(
            Entry(
                stringResource(Res.string.settings_profile),
                profileSummary,
                YacanaIcons.Profile,
                Screen.Profile,
                listOf(stringResource(Res.string.profile_database)),
            ),
            Entry(
                stringResource(Res.string.image_files),
                stringResource(Res.string.image_files_summary),
                YacanaIcons.Imaging,
                Screen.ImageFiles,
            ),
            Entry(
                stringResource(Res.string.camera_settings),
                stringResource(Res.string.camera_settings_summary, (profile?.options?.gain ?: DEFAULT_GAIN).toString()),
                YacanaIcons.Camera,
                Screen.CameraSettings,
                listOf(stringResource(Res.string.imaging_gain)),
            ),
            Entry(
                stringResource(Res.string.plate_solving),
                stringResource(Res.string.plate_solving_summary, profile?.options?.starDatabase ?: "D50"),
                YacanaIcons.Crosshair,
                Screen.PlateSolving,
                listOf(
                    stringResource(Res.string.solver_downsample),
                    stringResource(Res.string.solver_radius),
                    stringResource(Res.string.solver_timeout),
                    stringResource(Res.string.solver_centring_tolerance),
                    stringResource(Res.string.solver_centring_exposure),
                    stringResource(Res.string.solver_database),
                ),
            ),
            Entry(
                stringResource(Res.string.autofocus),
                stringResource(Res.string.af_settings_summary, fittingName(autofocus.fitting), 2 * autofocus.offsetSteps + 1),
                YacanaIcons.Focuser,
                Screen.AutofocusSettings,
                listOf(
                    stringResource(Res.string.af_step),
                    stringResource(Res.string.af_points),
                    stringResource(Res.string.af_fitting),
                    stringResource(Res.string.af_backlash_in),
                    stringResource(Res.string.af_backlash_out),
                ),
            ),
            Entry(
                stringResource(Res.string.safety),
                stringResource(Res.string.safety_summary),
                YacanaIcons.Shield,
                Screen.SafetySettings,
                listOf(
                    stringResource(Res.string.safety_stop),
                    stringResource(Res.string.safety_lost),
                    stringResource(Res.string.safety_battery),
                ),
            ),
            Entry(
                stringResource(Res.string.sky_settings),
                skySummary(caches),
                YacanaIcons.Sky,
                Screen.SkyMapSettings,
                listOf(stringResource(Res.string.sky_caches), stringResource(Res.string.sky_import), stringResource(Res.string.sky_layers)),
            ),
            Entry(stringResource(Res.string.display), stringResource(Res.string.display_summary), YacanaIcons.Display, Screen.Display),
            // As the design: "Same as the system · English", or the language chosen, each by its own name.
            Entry(
                stringResource(Res.string.language),
                chosen?.endonym ?: "${stringResource(Res.string.language_system)} · ${languages.system.endonym}",
                YacanaIcons.Language,
                Screen.Language,
                AppLanguage.entries.map { it.endonym },
            ),
            Entry(
                stringResource(Res.string.permissions),
                stringResource(Res.string.permissions_summary),
                YacanaIcons.Lock,
                Screen.Permissions,
            ),
            Entry(
                stringResource(Res.string.log_export),
                stringResource(Res.string.log_export_summary),
                YacanaIcons.FileExport,
                null,
                onClick = logExport?.let { it::export },
            ),
            Entry(stringResource(Res.string.about), stringResource(Res.string.about_summary), YacanaIcons.Info, Screen.About),
        )
    var query by rememberSaveable { mutableStateOf("") }
    val shown = entries.filter { it.matches(query) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchField(query, stringResource(Res.string.settings_search)) { query = it }
        if (shown.isEmpty()) {
            Text(
                stringResource(Res.string.settings_nothing_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            SectionCard {
                shown.forEachIndexed { index, entry ->
                    DeviceRow(entry.title, entry.summary, entry.icon, last = index == shown.lastIndex, onClick = {
                        entry.screen?.let(open) ?: entry.onClick?.invoke()
                    }) { RowChevron() }
                }
            }
        }
    }
}

/** The caches the map shows, by name, as the design's "Full cache (DSS) · Online photos"; or that there is none. */
@Composable
private fun skySummary(caches: SkyCacheFiles?): String {
    val shown = caches?.installed?.filter { it.inUse }.orEmpty()
    if (shown.isEmpty()) return stringResource(Res.string.sky_settings_none)
    return shown.map { it.title() }.joinToString(" · ")
}

/** An entry of Settings; the [options] inside its screen are found by name too. */
private class Entry(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    /** What it opens; or, without a screen, what it does, as exporting the log. */
    val screen: Screen?,
    val options: List<String> = emptyList(),
    val onClick: (() -> Unit)? = null,
) {
    /** Whether [query] is in the title, the summary or an option's name, ignoring case and accents. */
    fun matches(query: String): Boolean {
        val wanted = query.trim().plain()
        return wanted.isEmpty() || (listOf(title, summary) + options).any { wanted in it.plain() }
    }
}

private fun String.plain(): String = lowercase().map { ACCENTS[it] ?: it }.joinToString("")

private val ACCENTS = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'à' to 'a', 'è' to 'e', 'ï' to 'i')
