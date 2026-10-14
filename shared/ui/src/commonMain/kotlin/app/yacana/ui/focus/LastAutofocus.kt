// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.focus

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.focus.Autofocus
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.equipment.formatTemperature
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.focuser_autofocus
import app.yacana.ui.resources.focuser_last_autofocus
import app.yacana.ui.resources.focuser_last_autofocus_no_temperature
import app.yacana.ui.resources.focuser_last_curve
import app.yacana.ui.resources.focuser_last_curve_value
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** "Last autofocus 22:35 · at 12.9 °C", under the focuser's position, once there is one. */
@Composable
internal fun LastAutofocusLine(autofocus: Autofocus, zone: () -> TimeZone) {
    val last by autofocus.last.collectAsState()
    val result = last ?: return
    val time = result.at.toLocalDateTime(zone()).time
    val clock = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    Text(
        result.temperature?.let { stringResource(Res.string.focuser_last_autofocus, clock, formatTemperature(it)) }
            ?: stringResource(Res.string.focuser_last_autofocus_no_temperature, clock),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** "Last curve" as "Equipment · focuser" draws it, once there is one. */
@Composable
internal fun LastCurve(autofocus: Autofocus) {
    val last by autofocus.last.collectAsState()
    last?.let { result ->
        SectionCard {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(Res.string.focuser_last_curve),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(Res.string.focuser_last_curve_value, formatDecimal(result.hfr, 1, decimalComma()), result.position),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FocusChart(
                result.points,
                emptyList(),
                fittedCurve(result.points, result.fitting),
                result.position.toDouble(),
                settled = true,
                axes = false,
                modifier = Modifier.fillMaxWidth().height(84.dp).padding(top = 6.dp),
            )
        }
    }
}

/** The "Autofocus" button that opens the assistant. */
@Composable
internal fun AutofocusButton(onAutofocus: () -> Unit) {
    AccentButton(onClick = onAutofocus, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(min = Touch.Main)) {
        Icon(YacanaIcons.Focuser, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(stringResource(Res.string.focuser_autofocus), modifier = Modifier.padding(start = 6.dp))
    }
}
