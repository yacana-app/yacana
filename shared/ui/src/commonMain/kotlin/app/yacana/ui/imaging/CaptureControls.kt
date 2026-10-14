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
import app.yacana.ports.ColourStretch
import app.yacana.ports.Region
import app.yacana.ports.StarDetection
import app.yacana.ports.Stretch
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
import app.yacana.ui.resources.history
import app.yacana.ui.resources.imaging_abort
import app.yacana.ui.resources.imaging_auto_stretch
import app.yacana.ui.resources.imaging_binning
import app.yacana.ui.resources.imaging_binning_value
import app.yacana.ui.resources.imaging_capture
import app.yacana.ui.resources.imaging_dithering
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
import app.yacana.ui.resources.imaging_hfr
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
import app.yacana.ui.resources.imaging_stars
import app.yacana.ui.resources.imaging_stats
import app.yacana.ui.resources.imaging_target
import app.yacana.ui.resources.imaging_type
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import app.yacana.ui.resources.settings
import app.yacana.ui.resources.type_bias
import app.yacana.ui.resources.type_dark
import app.yacana.ui.resources.type_flat
import app.yacana.ui.resources.type_light
import app.yacana.ui.theme.LocalPhotoFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.time.Clock

/** What the camera is doing, over the top of the frame; nothing while it is idle. */
@Composable
internal fun ActivityChip(activity: CaptureActivity) {
    val comma = decimalComma()
    when (activity) {
        CaptureActivity.Idle -> Unit

        CaptureActivity.Downloading -> InfoChip(stringResource(Res.string.imaging_downloading))

        CaptureActivity.Dithering -> InfoChip(stringResource(Res.string.imaging_dithering))

        is CaptureActivity.Exposing -> InfoChip(
            stringResource(
                Res.string.imaging_exposing,
                formatDecimal(elapsedSeconds(activity).toDouble(), 0, comma),
                formatDecimal(activity.seconds, 1, comma),
            ),
        )
    }
}

/** The last frame's mean HFR and its number of stars, as NINA finds them; a tap shows the frame's details. */
@Composable
internal fun StarChips(stars: StarDetection, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClickLabel = stringResource(Res.string.imaging_last_frame), role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (stars.stars.isNotEmpty()) {
            InfoChip(stringResource(Res.string.imaging_hfr, formatDecimal(stars.averageHfr, 1, decimalComma())))
        }
        InfoChip(pluralStringResource(Res.plurals.imaging_stars, stars.stars.size, stars.stars.size))
    }
}

/** A small chip of information over the frame, as the design's HFR and star chips. */
@Composable
internal fun InfoChip(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier =
        Modifier
            .widthIn(max = INFO_CHIP_WIDTH)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Seconds of [exposure] gone, counted on the phone. */
@Composable
private fun elapsedSeconds(exposure: CaptureActivity.Exposing): Float {
    var elapsed by remember(exposure) { mutableFloatStateOf(0f) }
    LaunchedEffect(exposure) {
        while (true) {
            elapsed = ((Clock.System.now() - exposure.started).inWholeMilliseconds / 1000f).coerceIn(0f, exposure.seconds.toFloat())
            delay(TICK_MILLIS)
        }
    }
    return elapsed
}

/**
 * The design's round shutter: a tap takes a frame. While a frame is under way the ring shows the
 * exposure's progress, and a tap aborts it.
 */
@Composable
internal fun Shutter(activity: CaptureActivity, onClick: () -> Unit) {
    val busy = activity != CaptureActivity.Idle
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(SHUTTER)
            .clip(CircleShape)
            .clickable(
                onClickLabel = stringResource(if (busy) Res.string.imaging_abort else Res.string.imaging_capture),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (activity) {
            CaptureActivity.Idle -> Box(Modifier.fillMaxSize().border(3.dp, colors.onSurface, CircleShape))

            CaptureActivity.Downloading, CaptureActivity.Dithering -> CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 3.dp)

            is CaptureActivity.Exposing -> {
                val elapsed = elapsedSeconds(activity)
                CircularProgressIndicator(
                    progress = { (elapsed / activity.seconds.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 3.dp,
                    trackColor = colors.onSurface.copy(alpha = 0.3f),
                )
            }
        }
        // The design's accent fill: gold, or dark red in red mode.
        if (busy) {
            Box(Modifier.size(STOP).background(colors.secondaryContainer, RoundedCornerShape(6.dp)))
        } else {
            Box(Modifier.size(SHUTTER_INNER).background(colors.secondaryContainer, CircleShape))
        }
    }
}

/** The design's loop button beside the shutter; while a loop runs it stops it after the frame in progress. */
@Composable
internal fun LoopButton(looping: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        looping -> colors.primary
        enabled -> colors.onSurfaceVariant
        else -> colors.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
    }
    Column(
        Modifier
            .width(LOOP_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(YacanaIcons.Repeat, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Text(
            stringResource(if (looping) Res.string.imaging_loop_stop else Res.string.imaging_loop),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** The last frame, small and square, as a phone camera's thumbnail; a tap opens the history, as a phone camera's gallery. */
@Composable
internal fun Thumbnail(last: CapturedFrame?, stretch: ColourStretch, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, last?.frame, stretch) {
        val frame = last?.frame
        value = if (frame == null) {
            null
        } else {
            val side = min(frame.width, frame.height).toDouble()
            val region = Region((frame.width - side) / 2, (frame.height - side) / 2, side, side)
            withContext(Dispatchers.Default) {
                // A newer frame may replace and free this one meanwhile.
                try {
                    frame.preview(stretch, region, THUMBNAIL_PIXELS, THUMBNAIL_PIXELS).toImageBitmap()
                } catch (_: IllegalStateException) {
                    null
                }
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(THUMBNAIL)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(
                enabled = last != null,
                onClickLabel = stringResource(Res.string.history),
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        bitmap?.let {
            Image(
                it,
                contentDescription = stringResource(Res.string.imaging_last_frame),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = LocalPhotoFilter.current,
            )
        }
    }
}

private val SHUTTER = 80.dp
private val SHUTTER_INNER = 62.dp
private val STOP = 28.dp
private val THUMBNAIL = 56.dp
private const val THUMBNAIL_PIXELS = 160
private val LOOP_WIDTH = 64.dp
internal val INFO_CHIP_WIDTH = 260.dp
private const val DISABLED_ALPHA = 0.38f
private const val TICK_MILLIS = 250L
