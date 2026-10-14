// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.ui.YacanaIcons
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatDm
import app.yacana.ui.format.formatDms
import app.yacana.ui.format.formatHms

/**
 * Every component of the design system on one screen, with the theme's colours.
 * The screenshot tests capture it in both themes and orientations; it is not part of the app.
 */
@Composable
fun ComponentGallery() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Swatch("primary", colors.primary)
            Swatch("tertiary", colors.tertiary)
            Swatch("error", colors.error)
            Swatch("surface", colors.surfaceContainerHigh)
        }
        YacanaCard(title = "Mount", subtitle = "Connected · tracking", icon = YacanaIcons.Equipment) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Readout("RA", formatHms(0.7123))
                Readout("Dec", formatDms(41.2692))
                Readout("Alt", formatDm(52.17))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusLine("OK: connected", StatusKind.Ok)
            StatusLine("Busy: exposing 34 s of 60 s", StatusKind.Busy)
            StatusLine("Warning: battery optimization is on", StatusKind.Warning)
            StatusLine("Error: no answer from the mount", StatusKind.Error)
            StatusLine("Off: not configured", StatusKind.Off)
        }
        Readout("Temperature", "${formatDecimal(12.4, 1, comma = true)} °C", large = true)
        ListRow(title = "Dew heater", supporting = "On", trailing = { Switch(checked = true, onCheckedChange = {}) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton(onClick = {}) { Text("Go to") }
            OutlinedButton(onClick = {}) { Text("Sync") }
        }
        HoldButton(text = "Park", hint = "Hold to park", onConfirm = {})
        NumberPad(onKey = {}, onDelete = {}, deleteLabel = "Delete", decimal = ',')
    }
}
