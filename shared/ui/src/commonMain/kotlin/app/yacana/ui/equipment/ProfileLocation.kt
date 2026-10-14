// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Profile
import app.yacana.ui.AppPermission
import app.yacana.ui.PermissionsController
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.east_short
import app.yacana.ui.resources.north_short
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.profile_east
import app.yacana.ui.resources.profile_elevation
import app.yacana.ui.resources.profile_elevation_range
import app.yacana.ui.resources.profile_latitude
import app.yacana.ui.resources.profile_latitude_range
import app.yacana.ui.resources.profile_location
import app.yacana.ui.resources.profile_location_failed
import app.yacana.ui.resources.profile_location_no_permission
import app.yacana.ui.resources.profile_location_phone
import app.yacana.ui.resources.profile_location_phone_summary
import app.yacana.ui.resources.profile_location_typed
import app.yacana.ui.resources.profile_longitude
import app.yacana.ui.resources.profile_longitude_range
import app.yacana.ui.resources.profile_north
import app.yacana.ui.resources.profile_send_time
import app.yacana.ui.resources.profile_send_time_summary
import app.yacana.ui.resources.profile_south
import app.yacana.ui.resources.profile_west
import app.yacana.ui.resources.save
import app.yacana.ui.resources.south_short
import app.yacana.ui.resources.west_short
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which part of the site a sheet edits. */
private enum class SiteField { Latitude, Longitude, Elevation }

/** Where reading the phone's location stands. */
private enum class PhoneFix { Reading, Read, NoPermission, Failed }

/**
 * The design's location card: the phone's location or one typed in, and sending the time and
 * site to the mount. Typed in, as "Settings · profile and location · by hand": latitude and longitude
 * with their hemisphere, and altitude in metres, each on the app's keypad. The phone's is read
 * here, as the switch is turned on and while the card shows, after Android's permission if it is
 * missing; without it, or without a fix, the row says so and a tap tries again.
 */
@Composable
internal fun LocationCard(profile: Profile, permissions: PermissionsController, locate: suspend () -> Boolean, save: (Profile) -> Unit) {
    var editing by remember { mutableStateOf<SiteField?>(null) }
    val fromPhone = profile.options.locationFromPhone
    val site = profile.location ?: Location(0.0, 0.0)
    val statuses by permissions.statuses.collectAsState()
    val allowed = statuses.firstOrNull { it.permission == AppPermission.Location }?.granted != false
    var fix by remember { mutableStateOf(PhoneFix.Reading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(fromPhone, allowed, attempt) {
        if (!fromPhone) return@LaunchedEffect
        if (!allowed) {
            fix = PhoneFix.NoPermission
            return@LaunchedEffect
        }
        fix = PhoneFix.Reading
        fix = if (locate()) PhoneFix.Read else PhoneFix.Failed
    }
    SectionCard {
        Muted(stringResource(Res.string.profile_location))
        DeviceRow(
            stringResource(Res.string.profile_location_phone),
            when {
                !fromPhone -> stringResource(Res.string.profile_location_typed)
                fix == PhoneFix.NoPermission -> stringResource(Res.string.profile_location_no_permission)
                fix == PhoneFix.Failed -> stringResource(Res.string.profile_location_failed)
                fix == PhoneFix.Read -> profile.location?.let { formatSite(it) }
                else -> null
            } ?: stringResource(Res.string.profile_location_phone_summary),
            YacanaIcons.MapPin,
            supportingColor = if (fromPhone && fix in setOf(PhoneFix.NoPermission, PhoneFix.Failed)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            onClick = when {
                !fromPhone -> null
                fix == PhoneFix.NoPermission -> ({ permissions.request(AppPermission.Location) })
                fix == PhoneFix.Failed -> ({ attempt++ })
                else -> null
            },
        ) {
            Switch(
                checked = fromPhone,
                onCheckedChange = { on ->
                    save(profile.copy(options = profile.options.copy(locationFromPhone = on)))
                    if (on && !allowed) permissions.request(AppPermission.Location)
                },
            )
        }
        if (!fromPhone) {
            val comma = decimalComma()
            SiteRow(Res.string.profile_latitude, coordinate(site.latitude, Res.string.north_short, Res.string.south_short, comma)) {
                editing =
                    SiteField.Latitude
            }
            SiteRow(Res.string.profile_longitude, coordinate(site.longitude, Res.string.east_short, Res.string.west_short, comma)) {
                editing =
                    SiteField.Longitude
            }
            SiteRow(Res.string.profile_elevation, "${site.elevationM.roundToInt()} m") { editing = SiteField.Elevation }
        }
        DeviceRow(
            stringResource(Res.string.profile_send_time),
            stringResource(Res.string.profile_send_time_summary),
            YacanaIcons.Clock,
            last = true,
        ) {
            Switch(
                checked = profile.options.sendTimeAndLocation,
                onCheckedChange = { save(profile.copy(options = profile.options.copy(sendTimeAndLocation = it))) },
            )
        }
    }
    editing?.let { field ->
        BottomSheet(onDismiss = { editing = null }) {
            SiteSheet(field, site) { changed ->
                editing = null
                save(profile.copy(location = changed))
            }
        }
    }
}

@Composable
private fun SiteRow(label: StringResource, value: String, onEdit: () -> Unit) {
    DeviceRow(stringResource(label), value, onClick = onEdit) {
        Icon(
            YacanaIcons.Pencil,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** `40.4167° N`: four decimals, about 10 m, with the hemisphere. */
@Composable
private fun coordinate(value: Double, positive: StringResource, negative: StringResource, comma: Boolean): String =
    "${formatDecimal(abs(value), COORDINATE_DECIMALS, comma)}° ${stringResource(if (value >= 0) positive else negative)}"

/** One part of the site on the app's keypad; latitude and longitude also choose their hemisphere. */
@Composable
private fun SiteSheet(field: SiteField, site: Location, onSave: (Location) -> Unit) {
    val comma = decimalComma()
    val separator = if (comma) ',' else '.'
    val (title, hint) =
        when (field) {
            SiteField.Latitude -> Res.string.profile_latitude to Res.string.profile_latitude_range
            SiteField.Longitude -> Res.string.profile_longitude to Res.string.profile_longitude_range
            SiteField.Elevation -> Res.string.profile_elevation to Res.string.profile_elevation_range
        }
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    when (field) {
        SiteField.Elevation -> NumberEntry(
            initial = site.elevationM.roundToInt().toString(),
            unit = "m",
            hint = stringResource(hint),
            saveLabel = stringResource(Res.string.save),
            deleteLabel = stringResource(Res.string.number_delete),
            decimal = null,
            valid = { it <= MAX_ELEVATION },
        ) { onSave(site.copy(elevationM = it)) }

        else -> {
            val latitude = field == SiteField.Latitude
            val current = if (latitude) site.latitude else site.longitude
            var positive by remember { mutableStateOf(current >= 0) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingChip(stringResource(if (latitude) Res.string.profile_north else Res.string.profile_east), onClick = {
                    positive = true
                }, selected = positive)
                SettingChip(stringResource(if (latitude) Res.string.profile_south else Res.string.profile_west), onClick = {
                    positive =
                        false
                }, selected = !positive)
            }
            val max = if (latitude) LATITUDE else LONGITUDE
            NumberEntry(
                initial = formatDecimal(abs(current), COORDINATE_DECIMALS, comma).trimEnd('0').trimEnd(separator),
                unit = "°",
                hint = stringResource(hint),
                saveLabel = stringResource(Res.string.save),
                deleteLabel = stringResource(Res.string.number_delete),
                decimal = separator,
                valid = { it <= max },
            ) { value ->
                val signed = if (positive) value else -value
                onSave(if (latitude) site.copy(latitude = signed) else site.copy(longitude = signed))
            }
        }
    }
}

private const val COORDINATE_DECIMALS = 4
private const val LATITUDE = 90.0
private const val LONGITUDE = 180.0
private const val MAX_ELEVATION = 9000.0
