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
import app.yacana.domain.profile.DEFAULT_GAIN
import app.yacana.domain.profile.StackingOptions
import app.yacana.ports.ColourStretch
import app.yacana.ports.Region
import app.yacana.ports.StarDetection
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
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.camera_gain_range_unknown
import app.yacana.ui.resources.imaging_abort
import app.yacana.ui.resources.imaging_auto_stretch
import app.yacana.ui.resources.imaging_binning
import app.yacana.ui.resources.imaging_binning_value
import app.yacana.ui.resources.imaging_capture
import app.yacana.ui.resources.imaging_dither
import app.yacana.ui.resources.imaging_dither_hint
import app.yacana.ui.resources.imaging_dither_off
import app.yacana.ui.resources.imaging_downloading
import app.yacana.ui.resources.imaging_exposing
import app.yacana.ui.resources.imaging_exposure
import app.yacana.ui.resources.imaging_exposure_other
import app.yacana.ui.resources.imaging_exposure_range
import app.yacana.ui.resources.imaging_failed
import app.yacana.ui.resources.imaging_filter
import app.yacana.ui.resources.imaging_fwhm
import app.yacana.ui.resources.imaging_fwhm_arcsec
import app.yacana.ui.resources.imaging_gain
import app.yacana.ui.resources.imaging_gain_kept
import app.yacana.ui.resources.imaging_gain_range
import app.yacana.ui.resources.imaging_gain_value
import app.yacana.ui.resources.imaging_go_to_equipment
import app.yacana.ui.resources.imaging_hfr_detail
import app.yacana.ui.resources.imaging_last_frame
import app.yacana.ui.resources.imaging_loop
import app.yacana.ui.resources.imaging_loop_stop
import app.yacana.ui.resources.imaging_mark_count
import app.yacana.ui.resources.imaging_mark_stars
import app.yacana.ui.resources.imaging_mark_stars_summary
import app.yacana.ui.resources.imaging_midtones
import app.yacana.ui.resources.imaging_no_camera
import app.yacana.ui.resources.imaging_no_frame
import app.yacana.ui.resources.imaging_not_saved
import app.yacana.ui.resources.imaging_offset
import app.yacana.ui.resources.imaging_options
import app.yacana.ui.resources.imaging_save
import app.yacana.ui.resources.imaging_saved
import app.yacana.ui.resources.imaging_shadows
import app.yacana.ui.resources.imaging_stars
import app.yacana.ui.resources.imaging_stats
import app.yacana.ui.resources.imaging_target
import app.yacana.ui.resources.imaging_type
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import app.yacana.ui.resources.settings
import app.yacana.ui.resources.stack_balanced
import app.yacana.ui.resources.stack_balanced_summary
import app.yacana.ui.resources.stack_live
import app.yacana.ui.resources.stack_live_summary
import app.yacana.ui.resources.type_bias
import app.yacana.ui.resources.type_dark
import app.yacana.ui.resources.type_flat
import app.yacana.ui.resources.type_light
import app.yacana.ui.theme.LocalPhotoFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.time.Clock

/** Exposure times offered with a tap, in seconds; any other is typed on the app's keypad (docs/design/STYLE.md). */
private val EXPOSURES = listOf(10.0, 20.0, 30.0, 60.0, 300.0)

/** The preset times, or any other on the app's keypad. */
@Composable
internal fun ExposureChoice(capture: CaptureController, info: CameraInfo, close: () -> Unit) {
    val settings = capture.state.collectAsState().value.settings
    val comma = decimalComma()
    val presets = EXPOSURES.filter { it >= info.exposureMin && it <= info.exposureMax }
    var other by remember { mutableStateOf(settings.exposureSeconds !in presets) }
    Text(stringResource(Res.string.imaging_exposure), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { seconds ->
            FilterChip(
                selected = !other && settings.exposureSeconds == seconds,
                onClick = {
                    capture.update(settings.copy(exposureSeconds = seconds))
                    close()
                },
                label = { Text("${formatSeconds(seconds, comma)} s") },
            )
        }
        FilterChip(selected = other, onClick = { other = true }, label = { Text(stringResource(Res.string.imaging_exposure_other)) })
    }
    if (other) {
        NumberEntry(
            initial = formatSeconds(settings.exposureSeconds, comma),
            unit = "s",
            hint = stringResource(
                Res.string.imaging_exposure_range,
                formatSeconds(info.exposureMin, comma),
                formatSeconds(info.exposureMax, comma),
            ),
            saveLabel = stringResource(Res.string.save),
            deleteLabel = stringResource(Res.string.number_delete),
            decimal = if (comma) ',' else '.',
            valid = { it >= info.exposureMin && it <= info.exposureMax },
        ) { seconds ->
            capture.update(settings.copy(exposureSeconds = seconds))
            close()
        }
    }
}

/** A gain range on the keypad, or the camera's named gains: the profile's gain, for every frame. */
@Composable
internal fun GainChoice(capture: CaptureController, info: CameraInfo, close: () -> Unit) {
    val settings = capture.state.collectAsState().value.settings
    Text(stringResource(Res.string.imaging_gain), style = MaterialTheme.typography.titleMedium)
    GainEntry(info.gain, settings.gain, stringResource(Res.string.imaging_gain_kept)) { gain ->
        capture.update(settings.copy(gain = gain))
        close()
    }
}

/**
 * [gain] on the keypad within [control]'s range, or one of its named gains; with no camera to
 * say ([control] null), any whole number. [note] says what the gain is for.
 */
@Composable
internal fun GainEntry(control: GainControl?, gain: Int?, note: String?, onSave: (Int) -> Unit) {
    when (control) {
        is GainControl.Range -> NumberEntry(
            initial = (gain ?: control.min).toString(),
            unit = "",
            hint = stringResource(Res.string.imaging_gain_range, control.min, control.max),
            saveLabel = stringResource(Res.string.save),
            deleteLabel = stringResource(Res.string.number_delete),
            decimal = null,
            valid = { it >= control.min && it <= control.max },
            note = note,
        ) { onSave(it.toInt()) }

        is GainControl.Named -> {
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                control.names.forEachIndexed { index, name ->
                    FilterChip(selected = gain == index, onClick = { onSave(index) }, label = { Text(name) })
                }
            }
        }

        GainControl.None -> Unit

        null -> NumberEntry(
            initial = (gain ?: DEFAULT_GAIN).toString(),
            unit = "",
            hint = stringResource(Res.string.camera_gain_range_unknown),
            saveLabel = stringResource(Res.string.save),
            deleteLabel = stringResource(Res.string.number_delete),
            decimal = null,
            valid = { it >= 0 },
            note = note,
        ) { onSave(it.toInt()) }
    }
}

@Composable
internal fun BinningChoice(capture: CaptureController, info: CameraInfo, close: () -> Unit) {
    val settings = capture.state.collectAsState().value.settings
    Text(stringResource(Res.string.imaging_binning), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (bin in 1..minOf(info.maxBinX, info.maxBinY)) {
            FilterChip(selected = settings.binning == bin, onClick = {
                capture.update(settings.copy(binning = bin))
                close()
            }, label = { Text("$bin×$bin") })
        }
    }
}

/**
 * The filter wheel's filters; the chip shows the wheel's live position, so it shows whether it
 * moved. The wheel turns in [scope], the screen's: the sheet closes at once, and a Seestar slow to
 * answer would otherwise never be told.
 */
@Composable
internal fun FilterChoice(equipment: EquipmentController, scope: CoroutineScope, close: () -> Unit) {
    val state by equipment.state.collectAsState()
    val wheel = (state.connection as? Connection.Connected)?.equipment?.filterWheel ?: return
    val position by wheel.position.collectAsState()
    Text(stringResource(Res.string.imaging_filter), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        wheel.names.forEachIndexed { index, name ->
            FilterChip(selected = position.value == index, onClick = {
                scope.act({ wheel.select(index) }) {}
                close()
            }, label = { Text(name) })
        }
    }
}

/** The thumbnail's sheet: the last frame's histogram, statistics and file. */
@Composable
internal fun LastFrameDetails(last: CapturedFrame, stars: StarDetection?, stretch: ColourStretch) {
    val comma = decimalComma()
    Text(stringResource(Res.string.imaging_last_frame), style = MaterialTheme.typography.titleMedium)
    Histogram(last.histogram, stretch.green)
    Text(
        stringResource(
            Res.string.imaging_stats,
            formatDecimal(last.statistics.mean, 0, comma),
            formatDecimal(last.statistics.median, 0, comma),
            formatDecimal(last.statistics.stdDev, 0, comma),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    stars?.let { found ->
        if (found.fwhm > 0) {
            val capture = last.capture
            val scale = capture.optics.focalLengthMm?.let { capture.camera.pixelSizeX * capture.binX / it * ARCSEC_PER_MICRON_PER_MM }
            Text(
                scale?.let {
                    stringResource(
                        Res.string.imaging_fwhm_arcsec,
                        formatDecimal(found.fwhm * it, 1, comma),
                        formatDecimal(found.fwhm, 2, comma),
                    )
                } ?: stringResource(Res.string.imaging_fwhm, formatDecimal(found.fwhm, 2, comma)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val count = pluralStringResource(Res.plurals.imaging_stars, found.stars.size, found.stars.size)
        Text(
            if (found.stars.isEmpty()) {
                count
            } else {
                stringResource(
                    Res.string.imaging_hfr_detail,
                    formatDecimal(found.averageHfr, 2, comma),
                    formatDecimal(found.hfrStdDev, 2, comma),
                    count,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        last.savedAs?.let { stringResource(Res.string.imaging_saved, it) } ?: stringResource(Res.string.imaging_not_saved),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The top bar menu's options: frame type, target, saving single frames, offset, the stretch and the stars marked. */
@Composable
@Suppress("LongParameterList") // The stretch lives in the screen, which keeps it across frames.
internal fun Options(
    capture: CaptureController,
    info: CameraInfo,
    auto: Boolean,
    shadows: Float,
    midtones: Float,
    autoStretch: ColourStretch?,
    onAuto: (Boolean) -> Unit,
    onShadows: (Float) -> Unit,
    onMidtones: (Float) -> Unit,
    display: DisplaySettings,
    stacking: StackingOptions? = null,
    onStacking: (StackingOptions) -> Unit = {},
) {
    val marks by display.markStars.collectAsState()
    val marked by display.markedStars.collectAsState()
    val settings = capture.state.collectAsState().value.settings
    fun change(block: (CaptureSettings) -> CaptureSettings) = capture.update(block(settings))
    Text(stringResource(Res.string.imaging_options), style = MaterialTheme.typography.titleMedium)
    Label(Res.string.imaging_type)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameType.entries.forEach { type ->
            FilterChip(selected = settings.type == type, onClick = { capture.setType(type) }, label = { Text(stringResource(type.title)) })
        }
    }
    var target by rememberSaveable(settings.target) { mutableStateOf(settings.target) }
    OutlinedTextField(
        value = target,
        onValueChange = { text ->
            target = text
            change { it.copy(target = text) }
        },
        label = { Text(stringResource(Res.string.imaging_target)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
    ListRow(
        title = stringResource(Res.string.imaging_save),
        trailing = { Switch(checked = settings.save, onCheckedChange = { on -> change { it.copy(save = on) } }) },
    )
    Label(Res.string.imaging_dither)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DITHER_CHOICES.forEach { every ->
            FilterChip(
                selected = settings.ditherEvery == every,
                onClick = { change { it.copy(ditherEvery = every) } },
                label = { Text(if (every == 0) stringResource(Res.string.imaging_dither_off) else every.toString()) },
            )
        }
    }
    Text(
        stringResource(Res.string.imaging_dither_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    (info.offset as? GainControl.Range)?.let { offset ->
        Label(Res.string.imaging_offset)
        Stepper(
            value = settings.offset ?: offset.min,
            onChange = { value -> change { it.copy(offset = value) } },
            range = offset.min..offset.max,
            steps = listOf(1, 10),
            format = { it.toString() },
        )
    }
    ListRow(
        title = stringResource(Res.string.imaging_auto_stretch),
        trailing = {
            Switch(checked = auto, onCheckedChange = { on ->
                if (!on && autoStretch != null) {
                    // Manual starts from the automatic values; it is linked, so from the green channel's.
                    onShadows(autoStretch.green.shadows)
                    onMidtones(autoStretch.green.midtones)
                }
                onAuto(on)
            })
        },
    )
    if (!auto) {
        Text(stringResource(Res.string.imaging_shadows), style = MaterialTheme.typography.bodyMedium)
        Slider(value = shadows, onValueChange = onShadows, valueRange = 0f..MAX_SHADOWS)
        Text(stringResource(Res.string.imaging_midtones), style = MaterialTheme.typography.bodyMedium)
        Slider(value = midtones, onValueChange = onMidtones, valueRange = MIN_MIDTONES..1f)
    }
    ListRow(
        title = stringResource(Res.string.imaging_mark_stars),
        supporting = stringResource(Res.string.imaging_mark_stars_summary),
        trailing = { Switch(checked = marks, onCheckedChange = display::setMarkStars) },
    )
    if (marks) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.imaging_mark_count), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Stepper(marked, display::setMarkedStars, DisplaySettings.MARKED_STARS, listOf(MARKED_STEP), { it.toString() })
        }
    }
    // Live stacking, off at first as marking the stars is.
    stacking?.let { options ->
        ListRow(
            title = stringResource(Res.string.stack_live),
            supporting = stringResource(Res.string.stack_live_summary),
            trailing = { Switch(checked = options.live, onCheckedChange = { onStacking(options.copy(live = it)) }) },
        )
        ListRow(
            title = stringResource(Res.string.stack_balanced),
            supporting = stringResource(Res.string.stack_balanced_summary),
            trailing = { Switch(checked = options.balanced, onCheckedChange = { onStacking(options.copy(balanced = it)) }) },
        )
    }
}

/** The stars marked go up and down ten at a time. */
private const val MARKED_STEP = 10

@Composable
private fun Label(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge)
}

internal val FrameType.title: StringResource
    get() =
        when (this) {
            FrameType.Light -> Res.string.type_light
            FrameType.Dark -> Res.string.type_dark
            FrameType.Flat -> Res.string.type_flat
            FrameType.Bias -> Res.string.type_bias
        }

private const val MAX_SHADOWS = 0.5f

/** Arcseconds per pixel from microns per millimetre, as N.I.N.A. converts them. */
private const val ARCSEC_PER_MICRON_PER_MM = 206.26480624709636
private const val MIN_MIDTONES = 0.001f

/** How often a loop of lights may dither: never, or every 3, 5 or 10 frames. */
private val DITHER_CHOICES = listOf(0, 3, 5, 10)
