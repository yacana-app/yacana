// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.profile.SafetyOptions
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.session.GuardState
import app.yacana.domain.session.SafeStopReason
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.focus.formatDuration
import app.yacana.ui.format.errorText
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.alert_details
import app.yacana.ui.resources.alert_lost
import app.yacana.ui.resources.alert_ok
import app.yacana.ui.resources.alert_paused
import app.yacana.ui.resources.alert_retry
import app.yacana.ui.resources.alert_retrying
import app.yacana.ui.resources.alert_retrying_of
import app.yacana.ui.resources.alert_safe_stop
import app.yacana.ui.resources.alert_safe_stop_done_park
import app.yacana.ui.resources.alert_safe_stop_done_tracking
import app.yacana.ui.resources.alert_safe_stop_pending
import app.yacana.ui.resources.alert_safe_stop_pending_park
import app.yacana.ui.resources.alert_safe_stop_pending_tracking
import app.yacana.ui.resources.alert_stop_in
import app.yacana.ui.resources.alert_tip
import app.yacana.ui.resources.alert_why_battery
import app.yacana.ui.resources.alert_why_failed
import app.yacana.ui.resources.alert_why_lost
import app.yacana.ui.resources.sequence_stop
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The design "Night · alert": what happened, what the app does about it and what the user can do,
 * while the session's guard reconnects or stops safely. Nothing while all is well.
 * While it reconnects, the [run]'s cards go under the warning.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // The warning says the guard's state, the run's and the profile's.
fun SessionAlert(
    guard: GuardState,
    paused: SequenceRun?,
    profileName: String,
    safety: SafetyOptions,
    clock: Clock,
    onRetry: () -> Unit,
    onDetails: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    run: @Composable () -> Unit = {},
) {
    // The counters tick every second.
    var now by remember { mutableStateOf(clock.now()) }
    LaunchedEffect(guard) {
        while (true) {
            now = clock.now()
            delay(1.seconds)
        }
    }
    when (guard) {
        GuardState.Watching -> Unit

        is GuardState.Reconnecting -> {
            Warning(YacanaIcons.WifiOff, stringResource(Res.string.alert_lost, profileName)) {
                val elapsed = formatDuration(now - guard.since)
                val attempts = guard.attempts
                Muted(
                    if (attempts == null) {
                        stringResource(Res.string.alert_retrying, guard.attempt + 1, elapsed)
                    } else {
                        stringResource(Res.string.alert_retrying_of, guard.attempt + 1, attempts, elapsed)
                    },
                )
                guard.stopAt?.let { stopAt ->
                    Text(stringResource(Res.string.alert_paused), modifier = Modifier.padding(top = 8.dp))
                    Muted(stringResource(Res.string.alert_stop_in, formatDuration((stopAt - now).coerceAtLeast(0.seconds))))
                }
                Buttons(stringResource(Res.string.alert_retry), onRetry, onDetails)
            }
            run()
            Tip()
            if (paused?.status == RunStatus.Paused) Stop(onStop)
        }

        is GuardState.SafeStop -> {
            val why =
                when (guard.reason) {
                    SafeStopReason.LostLink -> stringResource(Res.string.alert_why_lost, formatDuration(safety.waitMinutes.minutes))
                    SafeStopReason.SequenceFailed -> stringResource(Res.string.alert_why_failed, errorText(paused?.error.orEmpty()))
                    SafeStopReason.LowBattery -> stringResource(Res.string.alert_why_battery, safety.lowBatteryPercent)
                }
            if (guard.done) {
                Warning(YacanaIcons.Shield, stringResource(Res.string.alert_safe_stop)) {
                    Text(why)
                    Muted(
                        stringResource(
                            if (guard.parked) Res.string.alert_safe_stop_done_park else Res.string.alert_safe_stop_done_tracking,
                        ),
                    )
                    Buttons(stringResource(Res.string.alert_ok), onDismiss, onDetails)
                }
            } else {
                Warning(YacanaIcons.WifiOff, stringResource(Res.string.alert_safe_stop_pending)) {
                    Text(why)
                    Muted(
                        stringResource(
                            if (safety.park) Res.string.alert_safe_stop_pending_park else Res.string.alert_safe_stop_pending_tracking,
                        ),
                    )
                    Buttons(stringResource(Res.string.alert_retry), onRetry, onDetails)
                }
                Tip()
            }
        }
    }
}

/** The design's card, outlined in the error colour, with its icon and title. */
@Composable
private fun Warning(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable () -> Unit) {
    SectionCard(warning = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
        }
        Column(Modifier.padding(top = 6.dp)) { content() }
    }
}

/** The main action, and "Details", which goes to the equipment. */
@Composable
private fun Buttons(main: String, onMain: () -> Unit, onDetails: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentButton(onClick = onMain, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) { Text(main) }
        SurfaceButton(stringResource(Res.string.alert_details), onDetails, Modifier.weight(1f))
    }
}

/** The design's tip: what to check. */
@Composable
private fun Tip() {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(YacanaIcons.Bulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.alert_tip), modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** "Hold to stop", which ends the paused sequence. */
@Composable
private fun Stop(onStop: () -> Unit) {
    HoldButton(
        stringResource(Res.string.sequence_stop),
        null,
        onStop,
        modifier = Modifier.padding(horizontal = 14.dp),
        danger = true,
        icon = YacanaIcons.Stop,
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
