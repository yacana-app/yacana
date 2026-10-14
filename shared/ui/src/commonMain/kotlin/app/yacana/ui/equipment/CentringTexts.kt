// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.BelowHorizonException
import app.yacana.domain.solver.CentringFailure
import app.yacana.domain.solver.CentringState
import app.yacana.domain.solver.CentringStep
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDm
import app.yacana.ui.format.formatOffset
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.centring_done
import app.yacana.ui.resources.centring_error
import app.yacana.ui.resources.centring_exposing
import app.yacana.ui.resources.centring_no_mount
import app.yacana.ui.resources.centring_not_solved
import app.yacana.ui.resources.centring_previous
import app.yacana.ui.resources.centring_slewing
import app.yacana.ui.resources.centring_solving
import app.yacana.ui.resources.centring_too_far
import app.yacana.ui.resources.mount_below_horizon
import app.yacana.ui.solving.solveFailureText
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What centring is doing now, and how far the frame before was. */
@Composable
internal fun centringStepText(working: CentringState.Working): String {
    val step =
        stringResource(
            when (working.step) {
                CentringStep.Slewing -> Res.string.centring_slewing
                CentringStep.Exposing -> Res.string.centring_exposing
                CentringStep.Solving -> Res.string.centring_solving
            },
        )
    return working.offArcsec?.let { "$step ${stringResource(Res.string.centring_previous, formatOffset(it))}" } ?: step
}

/** How the last centring ended: how close and in how many attempts, or why not. */
@Composable
internal fun CentringResult(state: CentringState) {
    val (text, error) =
        when (state) {
            is CentringState.Centred ->
                pluralStringResource(Res.plurals.centring_done, state.attempts, formatOffset(state.offArcsec), state.attempts) to false

            is CentringState.Failed -> centringFailureText(state) to true

            else -> return
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun centringFailureText(state: CentringState.Failed): String = when (val reason = state.reason) {
    CentringFailure.NoMount -> stringResource(Res.string.centring_no_mount)

    CentringFailure.TooFar -> stringResource(Res.string.centring_too_far, formatOffset(state.offArcsec ?: 0.0), state.attempts)

    is CentringFailure.NotSolved -> stringResource(Res.string.centring_not_solved, solveFailureText(reason.reason))

    is CentringFailure.Error -> stringResource(
        Res.string.centring_error,
        when (val cause = reason.cause) {
            is BelowHorizonException ->
                stringResource(Res.string.mount_below_horizon, formatDm(cause.target.altitudeDeg), formatDm(cause.limitDeg))

            else -> cause.message?.let { errorText(it) } ?: cause::class.simpleName.orEmpty()
        },
    )
}
