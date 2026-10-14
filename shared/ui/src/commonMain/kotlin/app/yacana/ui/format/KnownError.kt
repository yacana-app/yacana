// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.format

import androidx.compose.runtime.Composable
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.error_altitude_needs_target
import app.yacana.ui.resources.error_connect_camera
import app.yacana.ui.resources.error_connect_equipment
import app.yacana.ui.resources.error_connect_mount
import app.yacana.ui.resources.error_frame_late
import app.yacana.ui.resources.error_frame_replaced
import app.yacana.ui.resources.error_goto_needs_target
import app.yacana.ui.resources.error_imaging_busy
import app.yacana.ui.resources.error_no_camera
import app.yacana.ui.resources.error_no_filter
import app.yacana.ui.resources.error_no_filter_wheel
import app.yacana.ui.resources.error_no_focuser
import app.yacana.ui.resources.error_no_location
import app.yacana.ui.resources.error_no_mount
import app.yacana.ui.resources.error_no_switch
import app.yacana.ui.resources.error_no_switches
import app.yacana.ui.resources.error_not_connected
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One of the domain's own errors: its text in each language and what it names, such as a filter. */
data class KnownError(val text: StringResource, val names: List<String>)

/**
 * The domain's own error in [raw], written in English for the journal, as one of the app's texts
 *; null for any other, such as a device's own message, which shows as it came.
 */
fun knownError(raw: String): KnownError? = KNOWN.firstNotNullOfOrNull { (pattern, text) ->
    pattern.matchEntire(raw.trim())?.let { KnownError(text, it.groupValues.drop(1)) }
}

/** [raw] in the user's language when it is one of the app's own errors; as it came otherwise. */
@Composable
fun errorText(raw: String): String {
    val known = knownError(raw) ?: return raw
    return stringResource(known.text, *known.names.toTypedArray())
}

private val KNOWN =
    listOf(
        Regex("The filter wheel has no (.+)") to Res.string.error_no_filter,
        Regex("There is no switch called (.+)") to Res.string.error_no_switch,
        Regex("The profile has no switches connected") to Res.string.error_no_switches,
        Regex("The profile has no camera connected") to Res.string.error_no_camera,
        Regex("The camera did not hand over its frame in time") to Res.string.error_frame_late,
        Regex("The profile has no mount connected") to Res.string.error_no_mount,
        Regex("Connect a camera in Equipment first") to Res.string.error_connect_camera,
        Regex("Connect a mount in Equipment first") to Res.string.error_connect_mount,
        Regex("Connect the equipment in Equipment first") to Res.string.error_connect_equipment,
        Regex("The equipment is not connected") to Res.string.error_not_connected,
        Regex("Imaging is taking frames") to Res.string.error_imaging_busy,
        Regex("The frame was replaced before it was solved") to Res.string.error_frame_replaced,
        Regex("Go to the target needs a block with a target") to Res.string.error_goto_needs_target,
        Regex("Waiting for the altitude needs a block with a target") to Res.string.error_altitude_needs_target,
        Regex("The profile has no location") to Res.string.error_no_location,
        Regex("The profile has no focuser connected") to Res.string.error_no_focuser,
        Regex("The profile has no filter wheel connected") to Res.string.error_no_filter_wheel,
    )
