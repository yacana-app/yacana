// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.ui.AppInfo
import app.yacana.ui.DisplaySettings
import app.yacana.ui.LogExport
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.RowChevron
import app.yacana.ui.design.SectionCard
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.about_astap
import app.yacana.ui.resources.about_astronomy
import app.yacana.ui.resources.about_erfa
import app.yacana.ui.resources.about_gaia
import app.yacana.ui.resources.about_hocus
import app.yacana.ui.resources.about_independent
import app.yacana.ui.resources.about_jpeg
import app.yacana.ui.resources.about_licences
import app.yacana.ui.resources.about_name
import app.yacana.ui.resources.about_nina
import app.yacana.ui.resources.about_openngc
import app.yacana.ui.resources.about_ported
import app.yacana.ui.resources.about_simbad
import app.yacana.ui.resources.about_version
import app.yacana.ui.resources.engine_info
import app.yacana.ui.resources.logo_background
import app.yacana.ui.resources.logo_foreground
import app.yacana.ui.resources.oss_summary
import app.yacana.ui.resources.oss_title
import app.yacana.ui.resources.red_brightness
import app.yacana.ui.resources.red_brightness_summary
import app.yacana.ui.resources.red_mode
import app.yacana.ui.resources.red_mode_summary
import app.yacana.ui.theme.LocalPhotoFilter
import app.yacana.ui.theme.YacanaThemeMode
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.math.roundToInt

/** Red mode and its brightness, as "Settings · display". */
@Composable
fun DisplayScreen(display: DisplaySettings) {
    val mode by display.themeMode.collectAsState()
    val brightness by display.redBrightness.collectAsState()
    val red = mode == YacanaThemeMode.Red
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            DeviceRow(stringResource(Res.string.red_mode), stringResource(Res.string.red_mode_summary), YacanaIcons.RedMode, last = true) {
                Switch(checked = red, onCheckedChange = { display.setThemeMode(if (it) YacanaThemeMode.Red else YacanaThemeMode.Dark) })
            }
        }
        SectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.red_brightness), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${(brightness * PERCENT).roundToInt()} %", style = MaterialTheme.typography.titleMedium)
            }
            Slider(value = brightness, onValueChange = display::setRedBrightness, valueRange = DisplaySettings.MIN_RED_BRIGHTNESS..1f)
            Text(
                stringResource(Res.string.red_brightness_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The app's logo, its version, its name and the licences, as "Settings · about": the main credits,
 * all of them a tap away, and that Yacana is independent.
 */
@Composable
fun AboutScreen(app: AppInfo, open: (Screen) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo()
            Text(
                stringResource(Res.string.about_version, app.versionName),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                // The engine ships inside each version of the app: its own number says nothing more.
                stringResource(Res.string.engine_info, app.engine.arch),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard { Text(stringResource(Res.string.about_name), style = MaterialTheme.typography.bodyLarge) }
        SectionCard {
            Text(
                stringResource(Res.string.about_licences),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Licence("Yacana", "MPL-2.0")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_astap), "MPL-2.0")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_gaia), "CC BY-NC 3.0 IGO")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence("Tabler Icons", "MIT")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_astronomy), "MIT")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_openngc), "CC BY-SA 4.0")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_simbad), "CDS")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_jpeg), "MIT · IJG")
            // Code ported from them keeps their licence and their authors in its files.
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_nina), "MPL-2.0")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence("Three Point Polar Alignment", "MPL-2.0")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_hocus), "MPL-2.0")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Licence(stringResource(Res.string.about_erfa), "BSD-3-Clause")
            Text(
                stringResource(Res.string.about_ported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            DeviceRow(
                stringResource(Res.string.oss_title),
                stringResource(Res.string.oss_summary),
                YacanaIcons.License,
                last = true,
                onClick = { open(Screen.Licences) },
            ) { RowChevron() }
        }
        Text(
            stringResource(Res.string.about_independent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/**
 * The launcher icon's two layers in a circle, as the phone shows it. The layers are 108 units
 * across with the visible circle in the middle 72, so they are drawn larger than the circle. In
 * red mode the photo filter turns them red, as the design's red logo.
 */
@Composable
private fun Logo() {
    val filter = LocalPhotoFilter.current
    Box(Modifier.size(LOGO).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(LOGO * ADAPTIVE_CANVAS / ADAPTIVE_VISIBLE)
        Image(vectorResource(Res.drawable.logo_background), contentDescription = null, modifier = layer, colorFilter = filter)
        Image(vectorResource(Res.drawable.logo_foreground), contentDescription = null, modifier = layer, colorFilter = filter)
    }
}

@Composable
private fun Licence(name: String, licence: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(licence, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val PERCENT = 100
private val LOGO = 96.dp
private const val ADAPTIVE_CANVAS = 108f
private const val ADAPTIVE_VISIBLE = 72f
