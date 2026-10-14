// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.CaptureActivity
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.LiveStacking
import app.yacana.domain.imaging.StackCalibration
import app.yacana.domain.imaging.StackKey
import app.yacana.domain.imaging.StackingState
import app.yacana.domain.profile.StackingOptions
import app.yacana.domain.sequence.TONIGHT_CALIBRATION
import app.yacana.ports.Region
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.StatusKind
import app.yacana.ui.design.StatusLine
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.months
import app.yacana.ui.resources.save
import app.yacana.ui.resources.stack_adding
import app.yacana.ui.resources.stack_alignment
import app.yacana.ui.resources.stack_alignment_value
import app.yacana.ui.resources.stack_colour
import app.yacana.ui.resources.stack_colour_balanced
import app.yacana.ui.resources.stack_colour_camera
import app.yacana.ui.resources.stack_colour_waiting
import app.yacana.ui.resources.stack_cover
import app.yacana.ui.resources.stack_cover_start
import app.yacana.ui.resources.stack_cover_text
import app.yacana.ui.resources.stack_dark_at
import app.yacana.ui.resources.stack_dark_label
import app.yacana.ui.resources.stack_dark_tonight
import app.yacana.ui.resources.stack_discarded
import app.yacana.ui.resources.stack_discarded_value
import app.yacana.ui.resources.stack_failed
import app.yacana.ui.resources.stack_files
import app.yacana.ui.resources.stack_flat_file
import app.yacana.ui.resources.stack_flat_label
import app.yacana.ui.resources.stack_flats_how
import app.yacana.ui.resources.stack_frames
import app.yacana.ui.resources.stack_hours_minutes
import app.yacana.ui.resources.stack_live
import app.yacana.ui.resources.stack_live_note
import app.yacana.ui.resources.stack_minutes
import app.yacana.ui.resources.stack_no_dark
import app.yacana.ui.resources.stack_no_flat
import app.yacana.ui.resources.stack_no_flat_any
import app.yacana.ui.resources.stack_none
import app.yacana.ui.resources.stack_not_aligned
import app.yacana.ui.resources.stack_of
import app.yacana.ui.resources.stack_reset
import app.yacana.ui.resources.stack_saved
import app.yacana.ui.resources.stack_saving
import app.yacana.ui.resources.stack_show_frame
import app.yacana.ui.resources.stack_show_stack
import app.yacana.ui.resources.stack_stacked
import app.yacana.ui.resources.stack_take_darks
import app.yacana.ui.resources.stack_taking_darks
import app.yacana.ui.resources.stack_time
import app.yacana.ui.resources.stack_time_value
import app.yacana.ui.resources.stack_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The live stack in the viewer, as the design "Imaging · live stacking": drawn by the
 * engine with one stretch for its three channels, its colour [balanced] or the camera's, and drawn
 * again where the screen is as each frame comes, so a zoom stays as it was.
 */
@Composable
internal fun StackViewer(stacking: LiveStacking, state: StackingState, balanced: Boolean, modifier: Modifier = Modifier) {
    val info = state.info ?: return
    ImageViewer(
        imageWidth = info.width,
        imageHeight = info.height,
        identity = state.key ?: Unit,
        content = state.version to balanced,
        draw = { region, width, height -> stacking.preview(balanced, region, width, height) },
        modifier = modifier,
    )
}

/** The design's "Frame | Stack" over the viewer's corner: the last frame or the stack. */
@Composable
internal fun ViewChoice(showStack: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(2.dp).selectableGroup()) {
        listOf(false to Res.string.stack_show_frame, true to Res.string.stack_show_stack).forEach { (stack, name) ->
            val selected = stack == showStack
            Text(
                stringResource(name),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                modifier =
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .selectable(selected, role = Role.Tab) { onChange(stack) }
                    .heightIn(min = STACK_CHOICE_HEIGHT)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** What the stack holds, "34 frames · 34 min", which opens its sheet, and "Adding no. 35…" while a frame goes in. */
@Composable
internal fun StackChips(state: StackingState, onOpen: () -> Unit) {
    val info = state.info ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IconChip(
            YacanaIcons.Stack,
            pluralStringResource(Res.plurals.stack_frames, info.frames, info.frames, stackTime(info.exposureSeconds)),
            Modifier.clickable(onClickLabel = stringResource(Res.string.stack_show_stack), role = Role.Button, onClick = onOpen),
        )
        if (state.adding) IconChip(YacanaIcons.Loader, stringResource(Res.string.stack_adding, info.frames + 1))
        state.error?.let { InfoChip(stringResource(Res.string.stack_failed, errorText(it)), MaterialTheme.colorScheme.error) }
    }
}

/** [InfoChip] with an icon before its text. */
@Composable
private fun IconChip(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = INFO_CHIP_WIDTH)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A stack's whole exposure: "34 min", "3 h 5 min". */
@Composable
internal fun stackTime(seconds: Double): String {
    val minutes = (seconds / SECONDS_PER_MINUTE).roundToLong().toInt()
    val hours = minutes / MINUTES_PER_HOUR
    return if (hours == 0) {
        stringResource(Res.string.stack_minutes, minutes)
    } else {
        stringResource(Res.string.stack_hours_minutes, hours, minutes % MINUTES_PER_HOUR)
    }
}

/**
 * The design "Imaging · stack": what the stack holds and what it left out, how it was aligned and
 * its colour balanced; stacking on or off; and saving it or, held, starting again.
 */
@Composable
@Suppress("LongMethod") // The sheet's readings and actions, in the design's order.
internal fun StackSheet(
    stacking: LiveStacking,
    state: StackingState,
    options: StackingOptions,
    onOptions: (StackingOptions) -> Unit,
    capture: CaptureController,
) {
    val scope = rememberCoroutineScope()
    val comma = decimalComma()
    val key = state.key
    val info = state.info
    val name = listOfNotNull(key?.target, key?.filter).joinToString(" · ")
    Text(
        if (name.isEmpty()) stringResource(Res.string.stack_show_stack) else stringResource(Res.string.stack_title, name),
        style = MaterialTheme.typography.titleMedium,
    )
    Column {
        Reading(stringResource(Res.string.stack_stacked), stringResource(Res.string.stack_of, state.frames, state.offered))
        val left = state.discarded.values.sum() + state.notAligned
        val reasons = state.discarded.keys.map { stringResource(it.text) } +
            if (state.notAligned > 0) listOf(stringResource(Res.string.stack_not_aligned)) else emptyList()
        Reading(
            stringResource(Res.string.stack_discarded),
            if (left ==
                0
            ) {
                stringResource(Res.string.stack_none)
            } else {
                stringResource(Res.string.stack_discarded_value, left, reasons.joinToString(", "))
            },
        )
        if (info != null && key != null) {
            Reading(
                stringResource(Res.string.stack_time),
                stringResource(
                    Res.string.stack_time_value,
                    stackTime(info.exposureSeconds),
                    info.frames,
                    formatSeconds(key.exposureSeconds, comma),
                ),
            )
        }
        state.rotationDeg?.let {
            Reading(
                stringResource(Res.string.stack_alignment),
                stringResource(Res.string.stack_alignment_value, formatDecimal(abs(it), 1, comma)),
            )
        }
        Reading(
            stringResource(Res.string.stack_colour),
            stringResource(
                when {
                    !options.balanced -> Res.string.stack_colour_camera
                    info?.starsBalanced == true -> Res.string.stack_colour_balanced
                    else -> Res.string.stack_colour_waiting
                },
            ),
        )
        if (key != null) {
            Reading(stringResource(Res.string.stack_dark_label), darkText(state.calibration, key, comma))
            Reading(stringResource(Res.string.stack_flat_label), flatText(state.calibration, key))
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.stack_live), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = options.live, onCheckedChange = { onOptions(options.copy(live = it)) })
    }
    Text(
        stringResource(Res.string.stack_live_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AccentButton(
        onClick = { scope.launch { stacking.save() } },
        modifier = Modifier.fillMaxWidth().heightIn(min = Touch.Main),
        enabled = state.frames >= LiveStacking.MIN_FRAMES && !state.saving,
    ) {
        Icon(YacanaIcons.Save, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(stringResource(Res.string.save), modifier = Modifier.padding(start = 8.dp))
    }
    when {
        state.saving -> StatusLine(stringResource(Res.string.stack_saving), StatusKind.Busy)

        state.savedAs != null -> StatusLine(
            stringResource(Res.string.stack_saved, state.savedFrames, state.savedAs.orEmpty()),
            StatusKind.Ok,
        )

        else -> Text(
            stringResource(Res.string.stack_files),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TakeDarks(capture)
    HoldButton(
        stringResource(Res.string.stack_reset),
        null,
        onConfirm = { scope.launch { stacking.reset() } },
        enabled = info != null,
        icon = YacanaIcons.Refresh,
        danger = true,
    )
}

/** The dark the stack's last frame lost: "dark at 18 °C", or what it lacks. */
@Composable
private fun darkText(calibration: StackCalibration, key: StackKey, comma: Boolean): String {
    val temperature = calibration.darkTemperatureC
    return when {
        !calibration.dark -> stringResource(Res.string.stack_no_dark, formatSeconds(key.exposureSeconds, comma))
        temperature != null -> stringResource(Res.string.stack_dark_at, formatDecimal(temperature, 0, comma))
        else -> stringResource(Res.string.stack_dark_tonight)
    }
}

/** The master flat of the stack's last frame: "MasterFlat_IR.fits of October 11", or how to take one. */
@Composable
private fun flatText(calibration: StackCalibration, key: StackKey): String {
    val file = calibration.flatFile?.substringAfterLast('/')
    val night = calibration.flatNight
    val months = stringArrayResource(Res.array.months)
    return when {
        calibration.flat && file != null && night != null ->
            stringResource(Res.string.stack_flat_file, file, night.day, months[night.month.ordinal]).replace("  ", " ")

        calibration.flat && file != null -> file

        else -> {
            val none = key.filter?.let { stringResource(Res.string.stack_no_flat, it) } ?: stringResource(Res.string.stack_no_flat_any)
            "$none · ${stringResource(Res.string.stack_flats_how)}"
        }
    }
}

/**
 * "Take 10 darks of 60 s" with Imaging's exposure, for the stack's calibration: behind the
 * dark filter, or, without one, once the telescope is covered, which a sheet asks first. While they
 * are taken, how many are done.
 */
@Composable
private fun TakeDarks(capture: CaptureController) {
    val state by capture.state.collectAsState()
    var covering by remember { mutableStateOf(false) }
    val comma = decimalComma()
    val darks = state.darks
    if (darks != null) {
        StatusLine(stringResource(Res.string.stack_taking_darks, darks.done, darks.count), StatusKind.Busy)
        return
    }
    if (covering) {
        Text(stringResource(Res.string.stack_cover), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(Res.string.stack_cover_text), style = MaterialTheme.typography.bodyMedium)
        AccentButton(
            onClick = {
                covering = false
                capture.takeDarks(DARKS)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.Main),
        ) { Text(stringResource(Res.string.stack_cover_start)) }
        return
    }
    SurfaceButton(
        stringResource(Res.string.stack_take_darks, DARKS, formatSeconds(state.settings.exposureSeconds, comma)),
        onClick = { if (capture.darkFilter() != null) capture.takeDarks(DARKS) else covering = true },
        modifier = Modifier.fillMaxWidth(),
        icon = YacanaIcons.Night,
        enabled = state.activity == CaptureActivity.Idle,
    )
}

/** Imaging's darks: as many as a set of the sequence's. */
private const val DARKS = TONIGHT_CALIBRATION

/** A reading of the sheet: its name, muted, and its value at the end of the line. */
@Composable
private fun Reading(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/**
 * A small drawing of the middle of the stack, 4:3 as the night's card, drawn again as frames come;
 * null while there is no stack.
 */
@Composable
internal fun rememberStackThumbnail(stacking: LiveStacking, state: StackingState, balanced: Boolean): ImageBitmap? {
    val info = state.info
    val thumbnail by produceState<ImageBitmap?>(null, state.version, balanced, info?.width) {
        if (info == null) {
            value = null
            return@produceState
        }
        val height = info.width * THUMBNAIL_HEIGHT / THUMBNAIL_WIDTH.toDouble()
        val region = Region(0.0, (info.height - height) / 2, info.width.toDouble(), height)
        value =
            withContext(Dispatchers.Default) {
                try {
                    stacking.preview(balanced, region, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)?.toImageBitmap()
                } catch (_: IllegalStateException) {
                    null
                }
            }
    }
    return thumbnail
}

private val STACK_CHOICE_HEIGHT = 36.dp
private const val SECONDS_PER_MINUTE = 60.0
private const val MINUTES_PER_HOUR = 60
private const val THUMBNAIL_WIDTH = 128
private const val THUMBNAIL_HEIGHT = 96
