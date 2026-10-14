// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.CalibratorStatus
import app.yacana.alpaca.CoverStatus
import app.yacana.domain.equipment.FlatPanelMediator
import app.yacana.domain.equipment.FlatPanelState
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.cover_closed
import app.yacana.ui.resources.cover_error
import app.yacana.ui.resources.cover_moving
import app.yacana.ui.resources.cover_open
import app.yacana.ui.resources.cover_unknown
import app.yacana.ui.resources.light_error
import app.yacana.ui.resources.light_not_ready
import app.yacana.ui.resources.light_off
import app.yacana.ui.resources.light_ready
import app.yacana.ui.resources.light_unknown
import app.yacana.ui.resources.panel_brightness
import app.yacana.ui.resources.panel_brightness_of
import app.yacana.ui.resources.panel_close
import app.yacana.ui.resources.panel_cover
import app.yacana.ui.resources.panel_halt
import app.yacana.ui.resources.panel_light
import app.yacana.ui.resources.panel_line_cover
import app.yacana.ui.resources.panel_line_light
import app.yacana.ui.resources.panel_note
import app.yacana.ui.resources.panel_open
import app.yacana.ui.resources.safety_monitor_every
import app.yacana.ui.resources.safety_monitor_note
import app.yacana.ui.resources.safety_monitor_safe
import app.yacana.ui.resources.safety_monitor_unsafe
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** "Equipment · flat panel" with the connected panel: what it sends, and the commands back to it. */
@Composable
internal fun FlatPanelScreen(panel: FlatPanelMediator, owner: String) {
    val live by panel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Unit) = scope.act(block) { failure = it.message }
    FlatPanelContent(
        live.value,
        panel.maxBrightness,
        owner,
        live.error ?: failure,
        FlatPanelActions(
            open = { act { panel.open() } },
            close = { act { panel.close() } },
            halt = { act { panel.halt() } },
            lightOn = { act { panel.lightOn(it) } },
            lightOff = { act { panel.lightOff() } },
        ),
    )
}

/** What the flat panel's screen asks of the panel. */
class FlatPanelActions(
    val open: () -> Unit,
    val close: () -> Unit,
    val halt: () -> Unit,
    val lightOn: (Int) -> Unit,
    val lightOff: () -> Unit,
)

/**
 * "Equipment · flat panel": the cover, open or closed, with "Open" and "Close"
 * ("Stop" while it moves); and the light, on or off, with its brightness from 0 to
 * [maxBrightness]. What the panel does not have does not show.
 */
@Composable
fun FlatPanelContent(state: FlatPanelState?, maxBrightness: Int, owner: String, error: String?, actions: FlatPanelActions) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val cover = state?.cover
        if (cover != CoverStatus.NotPresent) {
            SectionCard {
                Muted(owner)
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                DeviceRow(
                    stringResource(Res.string.panel_cover),
                    cover?.let {
                        stringResource(it.text)
                    } ?: "—",
                    YacanaIcons.Box,
                    last = true,
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cover == CoverStatus.Moving) {
                        SurfaceButton(stringResource(Res.string.panel_halt), actions.halt, Modifier.weight(1f), icon = YacanaIcons.Stop)
                    } else {
                        SurfaceButton(
                            stringResource(Res.string.panel_open),
                            actions.open,
                            Modifier.weight(1f),
                            icon = YacanaIcons.ArrowBarUp,
                        )
                        SurfaceButton(
                            stringResource(Res.string.panel_close),
                            actions.close,
                            Modifier.weight(1f),
                            icon = YacanaIcons.ArrowBarDown,
                        )
                    }
                }
            }
        }
        val light = state?.light
        if (light != CalibratorStatus.NotPresent && maxBrightness > 0) {
            Light(light, state?.brightness, maxBrightness, actions)
        }
        Muted(stringResource(Res.string.panel_note), Modifier.padding(horizontal = 20.dp))
    }
}

/** The light: on or off with a switch, and its brightness with a slider and −/+, sent when let go. */
@Composable
private fun Light(light: CalibratorStatus?, brightness: Int?, maxBrightness: Int, actions: FlatPanelActions) {
    val on = light == CalibratorStatus.Ready || light == CalibratorStatus.NotReady
    var chosen by remember(brightness) { mutableIntStateOf(brightness?.takeIf { it > 0 } ?: (maxBrightness / 2)) }
    fun set(value: Int) {
        chosen = value.coerceIn(0, maxBrightness)
        if (on) actions.lightOn(chosen)
    }
    SectionCard {
        DeviceRow(
            stringResource(Res.string.panel_light),
            light?.let { stringResource(it.text) } ?: "—",
            YacanaIcons.Bulb,
            last = true,
        ) { Switch(checked = on, onCheckedChange = { if (it) actions.lightOn(chosen) else actions.lightOff() }) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.panel_brightness), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${(chosen * PERCENT.toDouble() / maxBrightness).roundToInt()} %", style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = chosen.toFloat(),
            onValueChange = { chosen = it.roundToInt() },
            onValueChangeFinished = { set(chosen) },
            valueRange = 0f..maxBrightness.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { set(chosen - 1) }) { Icon(YacanaIcons.Minus, contentDescription = null) }
            IconButton(onClick = { set(chosen + 1) }) { Icon(YacanaIcons.Add, contentDescription = null) }
            Muted(stringResource(Res.string.panel_brightness_of, chosen, maxBrightness), Modifier.weight(1f).padding(start = 8.dp))
        }
    }
}

/**
 * "Equipment · safety monitor": what the safety monitor says, safe or not, and that the
 * app does not act on it.
 */
@Composable
fun SafetyMonitorContent(safe: Boolean?, owner: String, error: String?) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Muted(owner)
            Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (safe == false) YacanaIcons.Alert else YacanaIcons.ShieldCheck,
                    contentDescription = null,
                    tint = if (safe == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(40.dp),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        when (safe) {
                            true -> stringResource(Res.string.safety_monitor_safe)
                            false -> stringResource(Res.string.safety_monitor_unsafe)
                            null -> "—"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        ?: Muted(stringResource(Res.string.safety_monitor_every))
                }
            }
        }
        Muted(stringResource(Res.string.safety_monitor_note), Modifier.padding(horizontal = 20.dp))
    }
}

/** The flat panel in the Equipment list: "cover open · light off", leaving out what it does not have. */
@Composable
internal fun flatPanelLine(state: FlatPanelState, maxBrightness: Int): String = listOfNotNull(
    state.cover.takeIf { it != CoverStatus.NotPresent }?.let {
        stringResource(Res.string.panel_line_cover, stringResource(it.text).lowercase())
    },
    state.light.takeIf { it != CalibratorStatus.NotPresent && maxBrightness > 0 }?.let {
        stringResource(Res.string.panel_line_light, stringResource(it.text).lowercase())
    },
).joinToString(" · ")

private val CoverStatus.text: StringResource
    get() = when (this) {
        CoverStatus.Closed -> Res.string.cover_closed
        CoverStatus.Moving -> Res.string.cover_moving
        CoverStatus.Open -> Res.string.cover_open
        CoverStatus.NotPresent, CoverStatus.Unknown -> Res.string.cover_unknown
        CoverStatus.Error -> Res.string.cover_error
    }

private val CalibratorStatus.text: StringResource
    get() = when (this) {
        CalibratorStatus.Off -> Res.string.light_off
        CalibratorStatus.NotReady -> Res.string.light_not_ready
        CalibratorStatus.Ready -> Res.string.light_ready
        CalibratorStatus.NotPresent, CalibratorStatus.Unknown -> Res.string.light_unknown
        CalibratorStatus.Error -> Res.string.light_error
    }

private const val PERCENT = 100
