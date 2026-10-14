// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.yacana.domain.imaging.DiscardReason
import app.yacana.domain.session.DiaryEvent
import app.yacana.ui.YacanaIcons
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.focus.formatDuration
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatOffset
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.diary_attempts
import app.yacana.ui.resources.diary_back
import app.yacana.ui.resources.diary_back_short
import app.yacana.ui.resources.diary_centred
import app.yacana.ui.resources.diary_centred_detail
import app.yacana.ui.resources.diary_delta
import app.yacana.ui.resources.diary_discarded
import app.yacana.ui.resources.diary_discarded_detail
import app.yacana.ui.resources.diary_failed
import app.yacana.ui.resources.diary_finished
import app.yacana.ui.resources.diary_focus
import app.yacana.ui.resources.diary_focus_detail
import app.yacana.ui.resources.diary_focus_extra
import app.yacana.ui.resources.diary_focus_failed
import app.yacana.ui.resources.diary_focus_failed_detail
import app.yacana.ui.resources.diary_lost
import app.yacana.ui.resources.diary_lost_detail
import app.yacana.ui.resources.diary_not_centred
import app.yacana.ui.resources.diary_parked
import app.yacana.ui.resources.diary_paused_hand
import app.yacana.ui.resources.diary_restored
import app.yacana.ui.resources.diary_resumed
import app.yacana.ui.resources.diary_safe_battery
import app.yacana.ui.resources.diary_safe_failed
import app.yacana.ui.resources.diary_safe_lost
import app.yacana.ui.resources.diary_safe_stop
import app.yacana.ui.resources.diary_started
import app.yacana.ui.resources.diary_stopped
import app.yacana.ui.resources.diary_tracking_stopped
import app.yacana.ui.resources.diary_why_filter
import app.yacana.ui.resources.diary_why_hfr
import app.yacana.ui.resources.diary_why_temperature
import app.yacana.ui.resources.diary_why_time
import app.yacana.ui.resources.history_reason_bright
import app.yacana.ui.resources.history_reason_clouds
import app.yacana.ui.resources.history_reason_few_stars
import app.yacana.ui.resources.run_paused
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * How the diary tells an event: its icon and colour, what happened, the detail under it, and for
 * the summary's incidents, a [heading] and a [short] detail after the time, if any.
 */
internal class DiaryLine(
    val icon: ImageVector,
    val tint: Color?,
    val title: String,
    val detail: String?,
    val heading: String = title,
    val short: String? = null,
)

/** The diary's line for this event; null for a pause or a stop with a reason, which the events around it tell. */
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod") // One line per kind of event.
internal fun DiaryEvent.line(): DiaryLine? {
    val colors = MaterialTheme.colorScheme
    return when (this) {
        is DiaryEvent.Started -> DiaryLine(YacanaIcons.Play, null, stringResource(Res.string.diary_started), null)

        is DiaryEvent.Restored -> DiaryLine(YacanaIcons.Refresh, colors.primary, stringResource(Res.string.diary_restored), null)

        is DiaryEvent.Centred -> {
            val name = target.orEmpty()
            val attempts = pluralStringResource(Res.plurals.diary_attempts, attempts, attempts)
            DiaryLine(
                YacanaIcons.Target,
                if (centred) null else colors.primary,
                stringResource(if (centred) Res.string.diary_centred else Res.string.diary_not_centred, name),
                offArcsec?.let { stringResource(Res.string.diary_centred_detail, formatOffset(it), attempts) } ?: attempts,
            )
        }

        is DiaryEvent.Focused -> focused()

        is DiaryEvent.FocusFailed -> DiaryLine(
            YacanaIcons.Focus,
            colors.error,
            stringResource(Res.string.diary_focus_failed),
            stringResource(Res.string.diary_focus_failed_detail),
        )

        is DiaryEvent.LinkLost -> DiaryLine(
            YacanaIcons.WifiOff,
            colors.error,
            stringResource(Res.string.diary_lost),
            stringResource(Res.string.diary_lost_detail),
        )

        is DiaryEvent.LinkBack -> {
            val after = formatDuration(after)
            DiaryLine(
                YacanaIcons.Wifi,
                colors.tertiary,
                stringResource(Res.string.diary_back),
                "$after · ${pluralStringResource(Res.plurals.diary_attempts, attempts, attempts)}",
                heading = stringResource(Res.string.diary_back_short),
                short = after,
            )
        }

        // A safe pause is told by the lost link before it.
        is DiaryEvent.Paused ->
            if (reason ==
                null
            ) {
                DiaryLine(YacanaIcons.Pause, null, stringResource(Res.string.run_paused), stringResource(Res.string.diary_paused_hand))
            } else {
                null
            }

        is DiaryEvent.Resumed -> DiaryLine(YacanaIcons.Play, null, stringResource(Res.string.diary_resumed), null)

        is DiaryEvent.SafeStopped -> {
            val why =
                when (reason) {
                    "LowBattery" -> stringResource(Res.string.diary_safe_battery)
                    "SequenceFailed" -> stringResource(Res.string.diary_safe_failed)
                    else -> stringResource(Res.string.diary_safe_lost)
                }
            val done = stringResource(if (parked) Res.string.diary_parked else Res.string.diary_tracking_stopped)
            DiaryLine(YacanaIcons.Shield, colors.error, stringResource(Res.string.diary_safe_stop), "$why · ${done.lowercase()}")
        }

        is DiaryEvent.Stopped -> if (reason ==
            null
        ) {
            DiaryLine(YacanaIcons.Stop, null, stringResource(Res.string.diary_stopped), null)
        } else {
            null
        }

        is DiaryEvent.Parked -> DiaryLine(YacanaIcons.Parking, null, stringResource(Res.string.diary_parked), null)

        is DiaryEvent.Finished -> DiaryLine(YacanaIcons.CircleCheck, colors.tertiary, stringResource(Res.string.diary_finished), null)

        is DiaryEvent.Failed -> DiaryLine(
            YacanaIcons.CircleX,
            colors.error,
            stringResource(Res.string.diary_failed),
            reason?.let {
                errorText(it)
            },
        )

        is DiaryEvent.Discarded -> {
            val why =
                stringResource(
                    when (reason) {
                        DiscardReason.Clouds -> Res.string.history_reason_clouds
                        DiscardReason.FewStars -> Res.string.history_reason_few_stars
                        DiscardReason.BrightBackground -> Res.string.history_reason_bright
                    },
                )
            DiaryLine(
                YacanaIcons.Cloud,
                colors.primary,
                pluralStringResource(Res.plurals.diary_discarded, count, count, why),
                listOfNotNull(stringResource(Res.string.diary_discarded_detail), target, filter).joinToString(" · "),
            )
        }
    }
}

/** An autofocus: "Autofocus", or "Extra autofocus" with why a trigger asked for it. */
@Composable
private fun DiaryEvent.Focused.focused(): DiaryLine {
    val comma = decimalComma()
    val result =
        stringResource(Res.string.diary_focus_detail, formatDecimal(hfr, 1, comma), position) +
            (temperature?.let { " · ${formatDecimal(it, 1, comma)} °C" } ?: "")
    val trigger = trigger ?: return DiaryLine(YacanaIcons.Focus, null, stringResource(Res.string.diary_focus), result)
    val change = change
    val why =
        when {
            trigger.contains("Temperature") && change != null ->
                stringResource(Res.string.diary_why_temperature, formatDecimal(abs(change), 1, comma))

            trigger.contains("Time") -> stringResource(Res.string.diary_why_time)

            trigger.contains("Hfr") -> stringResource(Res.string.diary_why_hfr)

            trigger.contains("Filter") -> stringResource(Res.string.diary_why_filter)

            else -> null
        }
    val short =
        if (trigger.contains("Temperature") &&
            change != null
        ) {
            stringResource(Res.string.diary_delta, formatDecimal(abs(change), 1, comma))
        } else {
            why
        }
    return DiaryLine(
        YacanaIcons.Focus,
        MaterialTheme.colorScheme.primary,
        stringResource(Res.string.diary_focus_extra),
        listOfNotNull(why, result).joinToString(" · "),
        short = short,
    )
}
