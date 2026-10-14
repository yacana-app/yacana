// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.domain.profile.Optics
import app.yacana.domain.profile.Profile
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.SectionCard
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.profile_aperture
import app.yacana.ui.resources.profile_aperture_range
import app.yacana.ui.resources.profile_aperture_ratio
import app.yacana.ui.resources.profile_focal_length
import app.yacana.ui.resources.profile_focal_length_range
import app.yacana.ui.resources.profile_not_set
import app.yacana.ui.resources.profile_optics
import app.yacana.ui.resources.profile_optics_field
import app.yacana.ui.resources.profile_optics_hint
import app.yacana.ui.resources.profile_optics_no_camera
import app.yacana.ui.resources.profile_pixel_size
import app.yacana.ui.resources.profile_pixel_size_range
import app.yacana.ui.resources.profile_sensor
import app.yacana.ui.resources.profile_sensor_height
import app.yacana.ui.resources.profile_sensor_range
import app.yacana.ui.resources.profile_sensor_width
import app.yacana.ui.resources.save
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Which part of the optics a sheet edits: the sensor's width, then its height. */
private enum class OpticsField { FocalLength, Aperture, SensorWidth, SensorHeight, PixelSize }

/**
 * The design's optics card: the focal length, the aperture and the camera's sensor, each on the
 * app's keypad, and what they give, the scale and the field. The focal length sets the plate
 * solve's scale and the field the sky map and the framing draw; the camera brings its sensor when
 * it connects, and it can be typed, so that the night is planned without the equipment.
 */
@Composable
internal fun OpticsCard(profile: Profile, save: (Profile) -> Unit) {
    var editing by remember { mutableStateOf<OpticsField?>(null) }
    val optics = profile.optics
    val comma = decimalComma()
    val unset = stringResource(Res.string.profile_not_set)
    SectionCard {
        Muted(stringResource(Res.string.profile_optics))
        OpticsRow(YacanaIcons.Equipment, Res.string.profile_focal_length, optics.focalLengthMm?.let(::millimetres) ?: unset) {
            editing = OpticsField.FocalLength
        }
        val ratio = optics.focalLengthMm?.let { focal -> optics.apertureMm?.let { formatDecimal(focal / it, 1, comma) } }
        val aperture =
            optics.apertureMm?.let { mm ->
                ratio?.let { stringResource(Res.string.profile_aperture_ratio, millimetres(mm), it) }
                    ?: millimetres(mm)
            }
        OpticsRow(YacanaIcons.Aperture, Res.string.profile_aperture, aperture ?: unset) { editing = OpticsField.Aperture }
        val sensor = optics.sensorWidthPx?.let { width -> optics.sensorHeightPx?.let { height -> "$width × $height px" } }
        OpticsRow(YacanaIcons.Camera, Res.string.profile_sensor, sensor ?: unset) { editing = OpticsField.SensorWidth }
        val pixel = optics.pixelSizeMicrons?.let { "${micrometres(it, comma)} µm" }
        OpticsRow(YacanaIcons.ZoomIn, Res.string.profile_pixel_size, pixel ?: unset) { editing = OpticsField.PixelSize }
        Muted(
            listOfNotNull(field(optics, comma), stringResource(Res.string.profile_optics_hint)).joinToString(" "),
            Modifier.padding(vertical = 6.dp),
        )
    }
    editing?.let { field ->
        BottomSheet(onDismiss = { editing = null }) {
            OpticsSheet(field, optics) { changed ->
                // The sensor's height follows its width, as one more step.
                editing = if (field == OpticsField.SensorWidth) OpticsField.SensorHeight else null
                save(profile.copy(optics = changed))
            }
        }
    }
}

/** "With the camera: 2.25″/px · field 1.35° × 2.40°", or that the camera brings it; nothing without a focal length. */
@Composable
private fun field(optics: Optics, comma: Boolean): String? {
    val scale = optics.pixelSizeMicrons?.let(optics::pixelScaleArcsec)
    val field = optics.fieldDeg()
    return when {
        optics.focalLengthMm == null && optics.measuredPixelScaleArcsec == null -> null

        scale == null || field == null -> stringResource(Res.string.profile_optics_no_camera)

        else -> stringResource(
            Res.string.profile_optics_field,
            formatDecimal(scale, 2, comma),
            formatDecimal(field.first, 2, comma),
            formatDecimal(field.second, 2, comma),
        )
    }
}

private fun millimetres(value: Double) = "${value.roundToInt()} mm"

/** A pixel size with up to two decimals, as 2.9 or 3.76, without the zeros after them. */
private fun micrometres(value: Double, comma: Boolean) = formatDecimal(value, PIXEL_DECIMALS, comma).trimEnd('0').trimEnd(',', '.')

@Composable
private fun OpticsRow(icon: ImageVector, label: StringResource, value: String, onEdit: () -> Unit) {
    DeviceRow(stringResource(label), value, icon, onClick = onEdit) {
        Icon(
            YacanaIcons.Pencil,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The focal length or the aperture on the app's keypad, in whole millimetres, or the camera's
 * sensor in pixels and its pixels in micrometres. A new focal length forgets the scale a plate
 * solve measured, which was for the old one.
 */
@Composable
private fun OpticsSheet(field: OpticsField, optics: Optics, onSave: (Optics) -> Unit) {
    if (field != OpticsField.FocalLength && field != OpticsField.Aperture) {
        CameraSheet(field, optics, onSave)
        return
    }
    val focal = field == OpticsField.FocalLength
    Text(
        stringResource(if (focal) Res.string.profile_focal_length else Res.string.profile_aperture),
        style = MaterialTheme.typography.titleMedium,
    )
    NumberEntry(
        initial = (if (focal) optics.focalLengthMm else optics.apertureMm)?.roundToInt()?.toString().orEmpty(),
        unit = "mm",
        hint = stringResource(if (focal) Res.string.profile_focal_length_range else Res.string.profile_aperture_range),
        saveLabel = stringResource(Res.string.save),
        deleteLabel = stringResource(Res.string.number_delete),
        decimal = null,
        valid = { if (focal) it in FOCAL_LENGTH else it in APERTURE },
    ) { value ->
        onSave(if (focal) optics.copy(focalLengthMm = value, measuredPixelScaleArcsec = null) else optics.copy(apertureMm = value))
    }
}

/** The camera's sensor, as its specifications give it: pixels across and down, and their size. */
@Composable
private fun CameraSheet(field: OpticsField, optics: Optics, onSave: (Optics) -> Unit) {
    val pixel = field == OpticsField.PixelSize
    val title =
        when (field) {
            OpticsField.SensorWidth -> Res.string.profile_sensor_width
            OpticsField.SensorHeight -> Res.string.profile_sensor_height
            else -> Res.string.profile_pixel_size
        }
    val separator = if (decimalComma()) ',' else '.'
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    // Remembered by its step, or the width typed would stay in the height's entry.
    key(field) {
        NumberEntry(
            initial =
            when (field) {
                OpticsField.SensorWidth -> optics.sensorWidthPx?.toString()
                OpticsField.SensorHeight -> optics.sensorHeightPx?.toString()
                else -> optics.pixelSizeMicrons?.let { micrometres(it, separator == ',') }
            }.orEmpty(),
            unit = if (pixel) "µm" else "px",
            hint = stringResource(if (pixel) Res.string.profile_pixel_size_range else Res.string.profile_sensor_range),
            saveLabel = stringResource(Res.string.save),
            deleteLabel = stringResource(Res.string.number_delete),
            decimal = separator.takeIf { pixel },
            valid = { if (pixel) it in PIXEL_SIZE else it in SENSOR },
        ) { value ->
            onSave(
                when (field) {
                    OpticsField.SensorWidth -> optics.copy(sensorWidthPx = value.roundToInt())
                    OpticsField.SensorHeight -> optics.copy(sensorHeightPx = value.roundToInt())
                    else -> optics.copy(pixelSizeMicrons = value)
                },
            )
        }
    }
}

private val FOCAL_LENGTH = 10.0..10_000.0
private val APERTURE = 5.0..2_000.0
private val SENSOR = 100.0..20_000.0
private val PIXEL_SIZE = 1.0..30.0
private const val PIXEL_DECIMALS = 2
