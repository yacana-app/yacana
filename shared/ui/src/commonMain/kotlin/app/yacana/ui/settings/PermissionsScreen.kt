// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.ui.AppPermission
import app.yacana.ui.PermissionStatus
import app.yacana.ui.PermissionsController
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.permission_allow
import app.yacana.ui.resources.permission_allow_how
import app.yacana.ui.resources.permission_autostart
import app.yacana.ui.resources.permission_autostart_checked
import app.yacana.ui.resources.permission_autostart_denied
import app.yacana.ui.resources.permission_autostart_how
import app.yacana.ui.resources.permission_autostart_why
import app.yacana.ui.resources.permission_battery
import app.yacana.ui.resources.permission_battery_denied
import app.yacana.ui.resources.permission_battery_how
import app.yacana.ui.resources.permission_battery_why
import app.yacana.ui.resources.permission_blocked_how
import app.yacana.ui.resources.permission_granted
import app.yacana.ui.resources.permission_local_network
import app.yacana.ui.resources.permission_local_network_denied
import app.yacana.ui.resources.permission_local_network_why
import app.yacana.ui.resources.permission_location
import app.yacana.ui.resources.permission_location_denied
import app.yacana.ui.resources.permission_location_how
import app.yacana.ui.resources.permission_location_why
import app.yacana.ui.resources.permission_notifications
import app.yacana.ui.resources.permission_notifications_denied
import app.yacana.ui.resources.permission_notifications_why
import app.yacana.ui.resources.permission_open_settings
import app.yacana.ui.resources.permission_unused
import app.yacana.ui.resources.permission_unused_denied
import app.yacana.ui.resources.permission_unused_how
import app.yacana.ui.resources.permission_unused_why
import app.yacana.ui.resources.permissions_intro
import app.yacana.ui.resources.permissions_later
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The permissions, as "First steps · permissions": one card with a row each, saying
 * what it is for and, until it is granted, what will open and what to tap there. A permission
 * the system no longer asks for says what stops working and how to grant it in its settings.
 * Nobody should need a guide to set it up.
 */
@Composable
fun PermissionsList(permissions: PermissionsController, modifier: Modifier = Modifier, intro: Boolean = true) {
    val statuses by permissions.statuses.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (intro) {
            Text(
                stringResource(Res.string.permissions_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
        val needed = statuses.filter { it.needed }
        SectionCard {
            needed.forEachIndexed { index, status ->
                PermissionRow(status) { permissions.request(status.permission) }
                if (index != needed.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(
            stringResource(Res.string.permissions_later),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

/** The design's row: the icon, green once granted; the name and what it is for; and the action at the end. */
@Composable
private fun PermissionRow(status: PermissionStatus, onRequest: () -> Unit) {
    val texts = status.permission.texts
    val colors = MaterialTheme.colorScheme
    // Android cannot tell whether Xiaomi's Autostart is on: the row only knows it was opened, and keeps its button.
    val checkOnly = status.permission == AppPermission.Autostart
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            texts.icon,
            contentDescription = null,
            tint = if (status.granted) colors.tertiary else colors.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(texts.title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(texts.why), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            when {
                status.blocked -> {
                    Text(stringResource(texts.denied), style = MaterialTheme.typography.bodySmall, color = colors.error)
                    Text(stringResource(Res.string.permission_blocked_how), style = MaterialTheme.typography.bodySmall)
                }

                checkOnly && status.granted -> Text(
                    stringResource(Res.string.permission_autostart_checked),
                    style = MaterialTheme.typography.bodySmall,
                )

                !status.granted -> Text(stringResource(texts.how), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (status.granted && !checkOnly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(YacanaIcons.Check, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(Res.string.permission_granted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            val settings = status.blocked || status.permission in SETTINGS_ONLY
            SettingChip(
                stringResource(if (settings) Res.string.permission_open_settings else Res.string.permission_allow),
                onClick = onRequest,
            )
        }
    }
}

/** Granted on a settings screen, never through the system's permission dialog. */
private val SETTINGS_ONLY = setOf(AppPermission.Battery, AppPermission.KeepWhenUnused, AppPermission.Autostart)

private data class PermissionTexts(
    val title: StringResource,
    val why: StringResource,
    val denied: StringResource,
    val how: StringResource,
    val icon: ImageVector,
)

private val AppPermission.texts: PermissionTexts
    get() =
        when (this) {
            AppPermission.LocalNetwork -> PermissionTexts(
                Res.string.permission_local_network,
                Res.string.permission_local_network_why,
                Res.string.permission_local_network_denied,
                Res.string.permission_allow_how,
                YacanaIcons.Wifi,
            )

            AppPermission.Location -> PermissionTexts(
                Res.string.permission_location,
                Res.string.permission_location_why,
                Res.string.permission_location_denied,
                Res.string.permission_location_how,
                YacanaIcons.MapPin,
            )

            AppPermission.Notifications -> PermissionTexts(
                Res.string.permission_notifications,
                Res.string.permission_notifications_why,
                Res.string.permission_notifications_denied,
                Res.string.permission_allow_how,
                YacanaIcons.Bell,
            )

            AppPermission.Battery -> PermissionTexts(
                Res.string.permission_battery,
                Res.string.permission_battery_why,
                Res.string.permission_battery_denied,
                Res.string.permission_battery_how,
                YacanaIcons.Battery,
            )

            AppPermission.KeepWhenUnused -> PermissionTexts(
                Res.string.permission_unused,
                Res.string.permission_unused_why,
                Res.string.permission_unused_denied,
                Res.string.permission_unused_how,
                YacanaIcons.ClockPause,
            )

            AppPermission.Autostart -> PermissionTexts(
                Res.string.permission_autostart,
                Res.string.permission_autostart_why,
                Res.string.permission_autostart_denied,
                Res.string.permission_autostart_how,
                YacanaIcons.Refresh,
            )
        }

/** The permissions, from Settings. */
@Composable
fun PermissionsScreen(permissions: PermissionsController) {
    PermissionsList(permissions, Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp))
}
