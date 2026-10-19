// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.flats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.GainControl
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.flats.FilterFlats
import app.yacana.domain.flats.FlatSource
import app.yacana.domain.flats.FlatsAssistant
import app.yacana.domain.flats.FlatsFailure
import app.yacana.domain.flats.FlatsOptions
import app.yacana.domain.flats.FlatsPhase
import app.yacana.domain.flats.FlatsState
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.DARK_FILTER
import app.yacana.ui.Beeper
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.night.RunningButtons
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.cancel
import app.yacana.ui.resources.flats_adu
import app.yacana.ui.resources.flats_adu_hint
import app.yacana.ui.resources.flats_brightness
import app.yacana.ui.resources.flats_count
import app.yacana.ui.resources.flats_countdown_beeps
import app.yacana.ui.resources.flats_countdown_place
import app.yacana.ui.resources.flats_darks_after
import app.yacana.ui.resources.flats_darks_short
import app.yacana.ui.resources.flats_darks_taking
import app.yacana.ui.resources.flats_done
import app.yacana.ui.resources.flats_failed
import app.yacana.ui.resources.flats_filter_done
import app.yacana.ui.resources.flats_filter_finding
import app.yacana.ui.resources.flats_filter_pending
import app.yacana.ui.resources.flats_filters
import app.yacana.ui.resources.flats_finish
import app.yacana.ui.resources.flats_gain_where
import app.yacana.ui.resources.flats_last
import app.yacana.ui.resources.flats_mean
import app.yacana.ui.resources.flats_no_equipment
import app.yacana.ui.resources.flats_no_filter
import app.yacana.ui.resources.flats_saved
import app.yacana.ui.resources.flats_source_panel_hint
import app.yacana.ui.resources.flats_source_screen_hint
import app.yacana.ui.resources.flats_source_sky_hint
import app.yacana.ui.resources.flats_start
import app.yacana.ui.resources.flats_stopped
import app.yacana.ui.resources.flats_target_value
import app.yacana.ui.resources.flats_too_bright
import app.yacana.ui.resources.flats_too_dark
import app.yacana.ui.resources.imaging_gain
import app.yacana.ui.resources.type_bias
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * The flats assistant, as its designs: "Flats · before starting", where the light
 * comes from, the target, how many and which filters; "Flats", each filter's progress and the last
 * frame's mean, with "Pause" and "Hold to stop"; and how it ended. With the screen as the light
 * the phone itself is it, as "Flats · screen": the flats start, and go on after a pause, once
 * [COUNTDOWN_SECONDS] have left time to lay it on the telescope, with a beep from [beeper], and
 * another beep says they ended.
 */
@Composable
fun FlatsScreen(
    flats: FlatsAssistant,
    equipment: EquipmentController,
    capture: CaptureController,
    beeper: Beeper = Beeper {},
    onDone: () -> Unit,
) {
    val state by flats.state.collectAsState()
    val connection by equipment.state.collectAsState()
    val connected = (connection.connection as? Connection.Connected)?.equipment
    val current = state
    // The profile's gain, which the flats and their bias and darks take, by its name on a camera of named gains.
    val gain = capture.state.collectAsState().value.settings.gain?.let { gain ->
        (connected?.camera?.info?.gain as? GainControl.Named)?.names?.getOrNull(gain) ?: gain.toString()
    }
    // What starts once the phone lies on the telescope: the flats, or their resumption.
    var countdown by remember { mutableStateOf<(() -> Unit)?>(null) }
    val screen = current?.options?.source == FlatSource.Screen
    val running = current != null && current.phase in RUNNING
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (wasRunning && !running && screen) beeper.beep(end = true)
        wasRunning = running
    }
    val waiting = countdown
    when {
        waiting != null -> FlatsCountdown(
            onGo = {
                countdown = null
                beeper.beep(end = false)
                waiting()
            },
            onCancel = { countdown = null },
        )

        current == null -> FlatsSetup(
            connected != null,
            connected?.camera?.info?.maxAdu,
            connected?.filterWheel?.names.orEmpty(),
            connected?.flatPanel?.maxBrightness,
            gain,
        ) { options ->
            val settings = capture.state.value.settings
            val start = { flats.start(options.copy(gain = settings.gain, offset = settings.offset)) }
            if (options.source == FlatSource.Screen) countdown = start else start()
        }

        running -> FlatsProgress(
            current,
            connected?.flatPanel?.maxBrightness ?: 0,
            flats::pause,
            { if (screen) countdown = flats::resume else flats.resume() },
            flats::stop,
        )

        else -> FlatsEnd(current, onDone)
    }
}

/**
 * "Flats · before starting", with the equipment as plain values: whether it is [connected], the
 * camera's [maxAdu], the wheel's [names], and the flat panel's [panelMaxBrightness] if there is one.
 * The light is the panel if there is one, and else this phone, which a card explains.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod") // The design's cards, in order.
fun FlatsSetup(
    connected: Boolean,
    maxAdu: Int?,
    names: List<String>,
    panelMaxBrightness: Int?,
    gain: String? = null,
    onStart: (FlatsOptions) -> Unit,
) {
    val light = names.filterNot { it.equals(DARK_FILTER, ignoreCase = true) }
    val panel = panelMaxBrightness != null
    val max = maxAdu ?: DEFAULT_MAX_ADU
    val source = if (panel) FlatSource.Panel else FlatSource.Screen
    var target by remember(max) { mutableIntStateOf(defaultTarget(max)) }
    var count by remember { mutableIntStateOf(COUNTS[1]) }
    var chosen by remember(light) { mutableStateOf(light.toSet()) }
    val maxBrightness = panelMaxBrightness ?: 0
    var brightness by remember(maxBrightness) { mutableIntStateOf(maxBrightness * DEFAULT_BRIGHTNESS / PERCENT) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!connected) Muted(stringResource(Res.string.flats_no_equipment), Modifier.padding(horizontal = 20.dp))
            SectionCard { Muted(stringResource(source.hint)) }
            SectionCard {
                Stepper(stringResource(Res.string.flats_adu), thousands(target), { target = (target - ADU_STEP).coerceAtLeast(ADU_STEP) }) {
                    target = (target + ADU_STEP).coerceAtMost(max - ADU_STEP)
                }
                Muted(stringResource(Res.string.flats_adu_hint, thousands(max)))
                if (panel && maxBrightness > 0) {
                    val step = (maxBrightness * BRIGHTNESS_STEP / PERCENT).coerceAtLeast(1)
                    Stepper(stringResource(Res.string.flats_brightness), "${brightness * PERCENT / maxBrightness} %", {
                        brightness = (brightness - step).coerceAtLeast(step)
                    }) { brightness = (brightness + step).coerceAtMost(maxBrightness) }
                }
                Muted(stringResource(Res.string.flats_count), Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COUNTS.forEach { SettingChip(it.toString(), { count = it }, selected = count == it) }
                }
                gain?.let { Line(stringResource(Res.string.imaging_gain), stringResource(Res.string.flats_gain_where, it)) }
            }
            if (light.isNotEmpty()) {
                SectionCard {
                    Muted(stringResource(Res.string.flats_filters))
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        light.forEach { name ->
                            val on = name in chosen
                            SettingChip(name, {
                                chosen = if (on) chosen - name else chosen + name
                            }, selected = on, icon = if (on) YacanaIcons.Check else null)
                        }
                    }
                }
            }
        }
        AccentButton(
            onClick = { onStart(FlatsOptions(source, target, count, light.filter { it in chosen }, brightness, darks = false)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = Touch.Main),
            enabled = connected && maxAdu != null && (light.isEmpty() || chosen.isNotEmpty()),
        ) {
            Icon(YacanaIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.flats_start), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** "Flats": the target, each filter's progress, the last frame's mean, and the buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlatsProgress(state: FlatsState, maxBrightness: Int, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val options = state.options
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCard {
                Line(stringResource(Res.string.flats_adu), stringResource(Res.string.flats_target_value, thousands(options.targetAdu)))
                if (options.source == FlatSource.Panel && maxBrightness > 0) {
                    Line(stringResource(Res.string.flats_brightness), "${options.panelBrightness * PERCENT / maxBrightness} %")
                }
            }
            SectionCard {
                state.filters.forEachIndexed { index, flats ->
                    Row(Modifier.fillMaxWidth().padding(top = if (index > 0) 6.dp else 0.dp)) {
                        Text(flats.filter ?: stringResource(Res.string.flats_no_filter), modifier = Modifier.weight(1f))
                        Muted(filterLine(state, index, flats))
                    }
                    if (index == state.current && state.phase == FlatsPhase.Taking) {
                        LinearProgressIndicator(
                            progress = { flats.done.toFloat() / flats.count },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            drawStopIndicator = {},
                        )
                    }
                }
                // The bias the flats end with behind the dark filter, and the darks if asked for.
                if (options.darks || state.phase == FlatsPhase.Darks) {
                    val darks = if (state.phase == FlatsPhase.Darks) Res.string.flats_darks_taking else Res.string.flats_darks_after
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(
                            stringResource(if (options.darks) Res.string.flats_darks_short else Res.string.type_bias),
                            modifier = Modifier.weight(1f),
                        )
                        Muted(stringResource(darks))
                    }
                }
            }
            state.lastMean?.let { mean ->
                SectionCard {
                    Row(Modifier.fillMaxWidth()) {
                        Muted(stringResource(Res.string.flats_last), Modifier.weight(1f))
                        Muted(stringResource(Res.string.flats_mean, thousands(mean.roundToInt())))
                    }
                }
            }
        }
        RunningButtons(state.paused, onPause, onResume, onStop)
    }
}

/** How the flats ended: each filter's, or why they stopped; "Done" closes the assistant. */
@Composable
fun FlatsEnd(state: FlatsState, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCard {
                val title =
                    when (state.phase) {
                        FlatsPhase.Done -> Res.string.flats_done
                        FlatsPhase.Stopped -> Res.string.flats_stopped
                        else -> Res.string.flats_failed
                    }
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                if (state.phase == FlatsPhase.Done) Muted(stringResource(Res.string.flats_saved), Modifier.padding(top = 4.dp))
                when (val failure = state.failure) {
                    FlatsFailure.TooBright -> Muted(stringResource(Res.string.flats_too_bright))
                    FlatsFailure.TooDark -> Muted(stringResource(Res.string.flats_too_dark))
                    is FlatsFailure.Error -> Text(errorText(failure.message), color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                state.filters.forEach { flats ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(flats.filter ?: stringResource(Res.string.flats_no_filter), modifier = Modifier.weight(1f))
                        Muted(doneLine(flats))
                    }
                }
            }
        }
        AccentButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = Touch.Main),
        ) {
            Text(stringResource(Res.string.flats_finish))
        }
    }
}

/** "1.8 s · 18 of 30", "finding the exposure…" or "pending · 0 of 30". */
@Composable
private fun filterLine(state: FlatsState, index: Int, flats: FilterFlats): String = when {
    index > state.current -> stringResource(Res.string.flats_filter_pending, flats.done, flats.count)
    index == state.current && state.phase == FlatsPhase.Finding -> stringResource(Res.string.flats_filter_finding)
    else -> doneLine(flats)
}

@Composable
private fun doneLine(flats: FilterFlats): String {
    val comma = decimalComma()
    val seconds = flats.exposureSeconds?.let { "${formatDecimal(it, if (it < 1) 2 else 1, comma)} s · " }.orEmpty()
    return seconds + stringResource(Res.string.flats_filter_done, flats.done, flats.count)
}

/** A name, and its value between − and +. */
@Composable
private fun Stepper(name: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) { Icon(YacanaIcons.Minus, contentDescription = null) }
        Text(value, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onPlus) { Icon(YacanaIcons.Add, contentDescription = null) }
    }
}

@Composable
private fun Line(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(name, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private val FlatSource.hint: StringResource
    get() = when (this) {
        FlatSource.Panel -> Res.string.flats_source_panel_hint
        FlatSource.Sky -> Res.string.flats_source_sky_hint
        FlatSource.Screen -> Res.string.flats_source_screen_hint
    }

/** "30 000": the digits in groups of three, as the design writes ADU. */
private fun thousands(value: Int): String = value.toString().reversed().chunked(GROUP).joinToString(" ").reversed()

/** Near half of the camera's maximum, in whole thousands: 30 000 of 65 535. */
private fun defaultTarget(maxAdu: Int): Int = ((maxAdu * TARGET_SHARE / ADU_STEP).roundToInt() * ADU_STEP).coerceAtLeast(ADU_STEP)

private val RUNNING = setOf(FlatsPhase.Preparing, FlatsPhase.Finding, FlatsPhase.Taking, FlatsPhase.Darks)

private val COUNTS = listOf(20, 30, 50)
private const val DEFAULT_MAX_ADU = 65_535
private const val ADU_STEP = 1_000
private const val TARGET_SHARE = 0.46
private const val PERCENT = 100
private const val DEFAULT_BRIGHTNESS = 40
private const val BRIGHTNESS_STEP = 5
private const val GROUP = 3
