// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.CameraInfo
import app.yacana.alpaca.GainControl
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.imaging.CaptureActivity
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.CaptureSettings
import app.yacana.domain.imaging.CapturedFrame
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.StackingOptions
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.ports.ColourStretch
import app.yacana.ports.DetectedStar
import app.yacana.ports.Region
import app.yacana.ports.Stretch
import app.yacana.ui.DisplaySettings
import app.yacana.ui.Section
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.ListRow
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.StatusKind
import app.yacana.ui.design.StatusLine
import app.yacana.ui.design.Stepper
import app.yacana.ui.equipment.act
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.autofocus
import app.yacana.ui.resources.imaging_abort
import app.yacana.ui.resources.imaging_auto_stretch
import app.yacana.ui.resources.imaging_binning
import app.yacana.ui.resources.imaging_binning_value
import app.yacana.ui.resources.imaging_capture
import app.yacana.ui.resources.imaging_downloading
import app.yacana.ui.resources.imaging_exposing
import app.yacana.ui.resources.imaging_exposure
import app.yacana.ui.resources.imaging_exposure_other
import app.yacana.ui.resources.imaging_exposure_range
import app.yacana.ui.resources.imaging_failed
import app.yacana.ui.resources.imaging_filter
import app.yacana.ui.resources.imaging_gain
import app.yacana.ui.resources.imaging_gain_range
import app.yacana.ui.resources.imaging_gain_value
import app.yacana.ui.resources.imaging_go_to_equipment
import app.yacana.ui.resources.imaging_last_frame
import app.yacana.ui.resources.imaging_loop
import app.yacana.ui.resources.imaging_loop_stop
import app.yacana.ui.resources.imaging_midtones
import app.yacana.ui.resources.imaging_no_camera
import app.yacana.ui.resources.imaging_no_frame
import app.yacana.ui.resources.imaging_not_saved
import app.yacana.ui.resources.imaging_offset
import app.yacana.ui.resources.imaging_options
import app.yacana.ui.resources.imaging_save
import app.yacana.ui.resources.imaging_saved
import app.yacana.ui.resources.imaging_shadows
import app.yacana.ui.resources.imaging_stats
import app.yacana.ui.resources.imaging_target
import app.yacana.ui.resources.imaging_type
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.reference_action
import app.yacana.ui.resources.save
import app.yacana.ui.resources.settings
import app.yacana.ui.resources.solve_action
import app.yacana.ui.resources.type_bias
import app.yacana.ui.resources.type_dark
import app.yacana.ui.resources.type_flat
import app.yacana.ui.resources.type_light
import app.yacana.ui.solving.SolveSheet
import app.yacana.ui.theme.LocalPhotoFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.time.Clock

/** The sheet open over the screen, one per setting chip, plus the last frame's details and the live stack's. */
private enum class Sheet { Exposure, Gain, Binning, Filter, LastFrame, Solve, Stack }

/**
 * Manual capture, laid out as its design, "Imaging · manual capture":
 * like a phone's camera. The frame fills the screen, with what is happening at the top and the
 * histogram in a corner. Under it come the camera's settings as chips, each opening a sheet, then
 * the last frame, the shutter and the loop. The other options are in the top bar's menu, which
 * sets [optionsOpen]. In landscape the controls go to the side.
 *
 * The last frame opens the history; its HFR and stars, its details. Autofocus shows with a
 * focuser. "Reference" puts the photo of the caches of the frame's field over it, as the design
 * "Imaging · reference".
 *
 * With live stacking on, "Frame | Stack" goes between the last frame and the stack, which shows
 * how many frames it holds; that opens its sheet, as the designs "Imaging · live stacking" and
 * "Imaging · stack".
 */
@Composable
fun ImagingScreen(
    capture: CaptureController,
    equipment: EquipmentController,
    display: DisplaySettings,
    solving: PlateSolving,
    centring: Centring,
    open: (Screen) -> Unit,
    optionsOpen: Boolean = false,
    onOptionsClose: () -> Unit = {},
    caches: SkyCacheStore? = null,
) {
    val equipmentState by equipment.state.collectAsState()
    val camera = (equipmentState.connection as? Connection.Connected)?.equipment?.camera
    val focuser = (equipmentState.connection as? Connection.Connected)?.equipment?.focuser
    val openSettings = {
        onOptionsClose()
        open(Screen.Settings)
    }
    if (camera == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusLine(stringResource(Res.string.imaging_no_camera), StatusKind.Off)
            OutlinedButton(onClick = { open(Screen.Root(Section.Equipment)) }) {
                Text(stringResource(Res.string.imaging_go_to_equipment))
            }
        }
        // Without a camera the menu only leads to Settings.
        if (optionsOpen) {
            BottomSheet(onOptionsClose) {
                OutlinedButton(onClick = openSettings) { Text(stringResource(Res.string.settings)) }
            }
        }
        return
    }
    val state by capture.state.collectAsState()
    val stacking = capture.stacking
    val stackState = stacking?.state?.collectAsState()?.value
    val profile = equipmentState.active
    val stackOptions = profile?.options?.stacking ?: StackingOptions()
    val showStack by display.showStack.collectAsState()
    val stackReady = stacking != null && stackOptions.live && stackState?.info != null
    val scope = rememberCoroutineScope()
    val saveStacking = { changed: StackingOptions ->
        profile?.let { equipment.save(it.copy(options = it.options.copy(stacking = changed))) }
        // Turned off, the stack is saved if it grew and its memory freed.
        if (!changed.live) scope.launch { stacking?.end() }
    }
    var autoStretch by rememberSaveable { mutableStateOf(true) }
    var shadows by remember { mutableFloatStateOf(0f) }
    var midtones by remember { mutableFloatStateOf(0.5f) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val last = state.last
    val stretch = when {
        last == null -> ColourStretch.Linear
        autoStretch -> last.autoStretch
        else -> ColourStretch.linked(Stretch(shadows, midtones))
    }
    val busy = state.activity != CaptureActivity.Idle
    val solveState by solving.state.collectAsState()
    val markStars by display.markStars.collectAsState()
    val markedStars by display.markedStars.collectAsState()
    val marks =
        remember(state.stars, markStars, markedStars) {
            if (markStars) marked(state.stars?.stars.orEmpty(), markedStars) else emptyList()
        }
    val reference = rememberReference(solving)
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    LaunchedEffect(last) { reference.showing(last) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val viewer: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                val stackShown = stackReady && showStack
                val mosaic = stackState?.mosaic
                if (stackShown && stacking != null && mosaic != null) {
                    // A mosaic's panel: the stack is the whole mosaic as it is built.
                    MosaicViewer(stacking, mosaic, stackOptions.balanced, Modifier.fillMaxSize())
                } else if (stackShown && stacking != null && stackState != null) {
                    StackViewer(stacking, stackState, stackOptions.balanced, Modifier.fillMaxSize())
                } else if (last != null) {
                    FrameViewer(
                        last.frame,
                        stretch,
                        Modifier.fillMaxSize(),
                        marks = marks,
                        onShown = { capture.shown(last) },
                        onViewport = { viewport = it },
                    )
                    // The histogram is the frame's, so it goes with the frame under the reference.
                    if (reference.shown == null) {
                        Histogram(
                            last.histogram,
                            stretch.green,
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .size(HISTOGRAM_WIDTH, HISTOGRAM_HEIGHT),
                        )
                    }
                } else {
                    Text(
                        stringResource(Res.string.imaging_no_frame),
                        Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The reference goes over the frame, not over the stack.
                val shown = reference.shown.takeIf { !stackShown }
                // Only read with the reference on, so pinching the frame alone does not recompose here.
                val at = if (shown != null) viewport else null
                if (shown != null && at != null) ReferencePhoto(shown.second, at, caches, Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        shown != null -> ReferenceLabel(caches, open)
                        stackShown && stackState != null -> StackChips(stackState) { sheet = Sheet.Stack }
                        else -> state.stars?.let { StarChips(it) { sheet = Sheet.LastFrame } }
                    }
                    if (reference.solvingFrame) ReferenceSolving()
                    ActivityChip(state.activity)
                    state.error?.let { InfoChip(stringResource(Res.string.imaging_failed, errorText(it)), MaterialTheme.colorScheme.error) }
                }
                if (stackReady) ViewChoice(showStack, display::setShowStack, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                SettingChips(state.settings, camera.info, equipment) { sheet = it }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumbnail(last, stretch) { open(Screen.History) }
                    Shutter(state.activity, onClick = if (busy) capture::abort else capture::capture)
                    LoopButton(
                        state.looping,
                        enabled = !busy || state.looping,
                        onClick = if (state.looping) capture::stop else capture::startLoop,
                    )
                }
                // The design's actions; on the next line when the language's words do not fit beside each other.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingChip(stringResource(Res.string.solve_action), onClick = {
                        solving.solveLastFrame()
                        sheet = Sheet.Solve
                    }, icon = YacanaIcons.Crosshair)
                    if (focuser != null) {
                        SettingChip(stringResource(Res.string.autofocus), onClick = { open(Screen.Autofocus) }, icon = YacanaIcons.Focuser)
                    }
                    SettingChip(
                        stringResource(Res.string.reference_action),
                        onClick = { reference.toggle(last) { sheet = Sheet.Solve } },
                        selected = reference.shown != null,
                        icon = YacanaIcons.Imaging,
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                viewer(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(CONTROLS_WIDTH).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                viewer(Modifier.fillMaxWidth().weight(1f))
                controls(Modifier.fillMaxWidth())
            }
        }

        val close = { sheet = null }
        when (sheet) {
            Sheet.Exposure -> BottomSheet(close) { ExposureChoice(capture, camera.info, close) }

            Sheet.Gain -> BottomSheet(close) { GainChoice(capture, camera.info, close) }

            Sheet.Binning -> BottomSheet(close) { BinningChoice(capture, camera.info, close) }

            Sheet.Filter -> BottomSheet(close) { FilterChoice(equipment, scope, close) }

            Sheet.LastFrame -> last?.let { frame -> BottomSheet(close) { LastFrameDetails(frame, state.stars, stretch) } }

            Sheet.Solve -> BottomSheet({
                close()
                solving.dismiss()
            }) { SolveSheet(solveState, centring) }

            Sheet.Stack -> if (stacking != null && stackState != null) {
                BottomSheet(close) { StackSheet(stacking, stackState, stackOptions, saveStacking, capture) }
            }

            null -> Unit
        }
        if (optionsOpen) {
            BottomSheet(onOptionsClose) {
                Options(
                    capture,
                    camera.info,
                    autoStretch,
                    shadows,
                    midtones,
                    last?.autoStretch,
                    { autoStretch = it },
                    { shadows = it },
                    { midtones = it },
                    display,
                    stackOptions.takeIf { stacking != null },
                    saveStacking,
                )
                OutlinedButton(onClick = openSettings) { Text(stringResource(Res.string.settings)) }
            }
        }
    }
}

/** The stars marked on a frame, as N.I.N.A. picks them: all of them, or the [count] with the most light above the background. */
private fun marked(stars: List<DetectedStar>, count: Int): List<DetectedStar> =
    if (stars.size <= count) stars else stars.sortedByDescending { it.averageBrightness }.take(count)

/** The design's chips under the frame: exposure, gain, binning and filter, each as the camera allows. */
@Composable
private fun SettingChips(settings: CaptureSettings, info: CameraInfo, equipment: EquipmentController, onOpen: (Sheet) -> Unit) {
    val comma = decimalComma()
    val state by equipment.state.collectAsState()
    val wheel = (state.connection as? Connection.Connected)?.equipment?.filterWheel
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingChip("${formatSeconds(settings.exposureSeconds, comma)} s", onClick = { onOpen(Sheet.Exposure) })
        when (val gain = info.gain) {
            is GainControl.Range ->
                SettingChip(stringResource(Res.string.imaging_gain_value, (settings.gain ?: gain.min).toString()), onClick = {
                    onOpen(Sheet.Gain)
                })

            is GainControl.Named ->
                SettingChip(gain.names.getOrElse(settings.gain ?: 0) { "—" }, onClick = { onOpen(Sheet.Gain) })

            GainControl.None -> Unit
        }
        if (minOf(info.maxBinX, info.maxBinY) > 1) {
            SettingChip(stringResource(Res.string.imaging_binning_value, settings.binning), onClick = { onOpen(Sheet.Binning) })
        }
        if (wheel != null) {
            val position by wheel.position.collectAsState()
            SettingChip(position.value?.let { wheel.names.getOrNull(it) } ?: "—", onClick = { onOpen(Sheet.Filter) })
        }
    }
}

/** Seconds with as many decimals as they need, up to three: `20`, `0.5`, `0.001`. */
internal fun formatSeconds(seconds: Double, comma: Boolean): String {
    val decimals = (0..MAX_DECIMALS).first { decimals ->
        formatDecimal(seconds, decimals, false).toDouble() == seconds ||
            decimals == MAX_DECIMALS
    }
    return formatDecimal(seconds, decimals, comma)
}

private val CONTROLS_WIDTH = 320.dp
private val HISTOGRAM_WIDTH = 160.dp
private val HISTOGRAM_HEIGHT = 44.dp
private const val MAX_DECIMALS = 3
