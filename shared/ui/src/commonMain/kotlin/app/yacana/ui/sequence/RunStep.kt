// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import androidx.compose.runtime.Composable
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.CoolCamera
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.GoToTarget
import app.yacana.domain.sequence.MoveFocuser
import app.yacana.domain.sequence.Note
import app.yacana.domain.sequence.Park
import app.yacana.domain.sequence.RunAutofocus
import app.yacana.domain.sequence.SequenceItem
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.SetSwitch
import app.yacana.domain.sequence.StopTracking
import app.yacana.domain.sequence.SwitchFilter
import app.yacana.domain.sequence.Unpark
import app.yacana.domain.sequence.WaitFor
import app.yacana.domain.sequence.WaitUntilAbove
import app.yacana.domain.sequence.WaitUntilTime
import app.yacana.domain.sequence.WarmCamera
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.step_autofocus
import app.yacana.ui.resources.step_bias
import app.yacana.ui.resources.step_cool
import app.yacana.ui.resources.step_darks
import app.yacana.ui.resources.step_filter
import app.yacana.ui.resources.step_focuser
import app.yacana.ui.resources.step_goto
import app.yacana.ui.resources.step_lights
import app.yacana.ui.resources.step_note
import app.yacana.ui.resources.step_park
import app.yacana.ui.resources.step_stop_tracking
import app.yacana.ui.resources.step_switch
import app.yacana.ui.resources.step_unpark
import app.yacana.ui.resources.step_wait
import app.yacana.ui.resources.step_wait_altitude
import app.yacana.ui.resources.step_wait_time
import app.yacana.ui.resources.step_warm
import org.jetbrains.compose.resources.stringResource

/** The instruction running, with the name of the target around it. */
internal class RunStep(val item: SequenceItem, val target: String?, val done: Int)

/** The instruction at the run's [SequenceRun.current], found from the root down. */
internal fun SequenceRun.step(): RunStep? {
    var target = sequence.root.target?.name
    var item: SequenceItem? = sequence.root
    current.forEach { index ->
        val block = item as? Block
        item = block?.items?.getOrNull(index)
        (item as? Block)?.target?.let { target = it.name }
    }
    return item?.takeIf { it !is Block }?.let { RunStep(it, target, done[current] ?: 0) }
}

/** What the run is doing, in words: "Frames of M 31 · 5/20". */
@Composable
internal fun stepText(step: RunStep): String {
    val target = step.target.orEmpty()
    return when (val item = step.item) {
        Unpark -> stringResource(Res.string.step_unpark)

        Park -> stringResource(Res.string.step_park)

        is SetSwitch -> stringResource(Res.string.step_switch, item.name)

        is GoToTarget -> stringResource(Res.string.step_goto, target)

        RunAutofocus -> stringResource(Res.string.step_autofocus)

        is CoolCamera -> stringResource(Res.string.step_cool)

        WarmCamera -> stringResource(Res.string.step_warm)

        is Exposures ->
            when (item.type) {
                FrameType.Dark -> stringResource(Res.string.step_darks, step.done, item.count)
                FrameType.Bias -> stringResource(Res.string.step_bias, step.done, item.count)
                else -> stringResource(Res.string.step_lights, target, step.done, item.count)
            }

        is WaitUntilTime -> stringResource(Res.string.step_wait_time, clockText(item.minute))

        is WaitFor -> stringResource(Res.string.step_wait, formatDecimal(item.minutes, 0, decimalComma()))

        is WaitUntilAbove -> stringResource(Res.string.step_wait_altitude, target, formatDecimal(item.altitudeDeg, 0, decimalComma()))

        is SwitchFilter -> stringResource(Res.string.step_filter, item.filter)

        is MoveFocuser -> stringResource(Res.string.step_focuser, item.position)

        StopTracking -> stringResource(Res.string.step_stop_tracking)

        is Note -> stringResource(Res.string.step_note, item.text)

        is Block -> item.name
    }
}

/** [minute] of tonight's clock as the screens write it, "02:00". */
internal fun clockText(minute: Int): String =
    "${(minute / MINUTES_PER_HOUR % HOURS_PER_DAY).toString().padStart(2, '0')}:${(minute % MINUTES_PER_HOUR).toString().padStart(2, '0')}"

private const val MINUTES_PER_HOUR = 60

private const val HOURS_PER_DAY = 24
