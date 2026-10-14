// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.solving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.SolveFailure
import app.yacana.domain.solver.SolveState
import app.yacana.ports.Solution
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatDms
import app.yacana.ui.format.formatHms
import app.yacana.ui.format.formatOffset
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.solve_dec
import app.yacana.ui.resources.solve_error
import app.yacana.ui.resources.solve_j2000
import app.yacana.ui.resources.solve_no_database
import app.yacana.ui.resources.solve_no_frame
import app.yacana.ui.resources.solve_not_solved
import app.yacana.ui.resources.solve_ra
import app.yacana.ui.resources.solve_rotation
import app.yacana.ui.resources.solve_scale
import app.yacana.ui.resources.solve_scale_value
import app.yacana.ui.resources.solve_solved_blind
import app.yacana.ui.resources.solve_solved_mount
import app.yacana.ui.resources.solve_solving_blind
import app.yacana.ui.resources.solve_solving_mount
import app.yacana.ui.resources.solve_sync
import app.yacana.ui.resources.solve_sync_failed
import app.yacana.ui.resources.solve_sync_hint
import app.yacana.ui.resources.solve_synced
import app.yacana.ui.resources.solve_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Where the last frame points, as "Imaging · solve": while ASTAP solves, then the centre in
 * J2000, the rotation and the scale, or why there is no solution. With [centring] and a mount that
 * can sync, "Sync the mount" puts the mount on the solution.
 */
@Composable
internal fun SolveSheet(state: SolveState, centring: Centring? = null) {
    val comma = decimalComma()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(stringResource(Res.string.solve_title), style = MaterialTheme.typography.titleMedium)
    when (state) {
        SolveState.Idle -> Unit

        is SolveState.Solving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(if (state.blind) Res.string.solve_solving_blind else Res.string.solve_solving_mount),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }

        is SolveState.Solved -> {
            val seconds = formatDecimal(state.elapsed.inWholeMilliseconds / MILLIS, 1, comma)
            Text(
                stringResource(if (state.blind) Res.string.solve_solved_blind else Res.string.solve_solved_mount, seconds, state.database),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            val solution = state.solution
            val degrees = { pixels: Int -> formatDecimal(pixels * solution.scaleArcsec / ARCSEC_PER_DEGREE, 2, comma) }
            Value(stringResource(Res.string.solve_ra), formatHms(solution.centre.raHours))
            Value(stringResource(Res.string.solve_dec), formatDms(solution.centre.decDeg))
            Value(stringResource(Res.string.solve_rotation), "${formatDecimal(solution.rotationDeg, 1, comma)}°")
            Value(
                stringResource(Res.string.solve_scale),
                stringResource(
                    Res.string.solve_scale_value,
                    formatDecimal(solution.scaleArcsec, 2, comma),
                    degrees(state.frameWidth),
                    degrees(state.frameHeight),
                ),
            )
            Text(
                stringResource(Res.string.solve_j2000),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (centring?.canSync == true) SyncMount(centring, solution)
        }

        is SolveState.Failed -> Text(solveFailureText(state.reason), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Why a frame did not solve, in the user's words. */
@Composable
internal fun solveFailureText(reason: SolveFailure): String = when (reason) {
    SolveFailure.NoFrame -> stringResource(Res.string.solve_no_frame)

    is SolveFailure.NoDatabase -> stringResource(Res.string.solve_no_database, reason.name)

    is SolveFailure.NotSolved -> stringResource(
        Res.string.solve_not_solved,
        reason.database,
        formatDecimal(reason.elapsed.inWholeMilliseconds / MILLIS, 1, decimalComma()),
    )

    is SolveFailure.Error -> stringResource(Res.string.solve_error, reason.message)
}

/** "Sync the mount", then how far from the solution the mount says it is, or why it could not. */
@Composable
private fun SyncMount(centring: Centring, solution: Solution) {
    val scope = rememberCoroutineScope()
    var off by remember(solution) { mutableStateOf<Double?>(null) }
    var failure by remember(solution) { mutableStateOf<String?>(null) }
    SurfaceButton(
        stringResource(Res.string.solve_sync),
        {
            scope.launch {
                runCatching { centring.sync(solution) }
                    .onSuccess {
                        off = it
                        failure = null
                    }.onFailure { failure = it.message ?: it::class.simpleName.orEmpty() }
            }
        },
        Modifier.fillMaxWidth().padding(top = 10.dp),
        YacanaIcons.Crosshair,
    )
    val text =
        failure?.let { stringResource(Res.string.solve_sync_failed, it) }
            ?: off?.let { stringResource(Res.string.solve_synced, formatOffset(it)) }
            ?: stringResource(Res.string.solve_sync_hint)
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Value(label: String, value: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val MILLIS = 1000.0
private const val ARCSEC_PER_DEGREE = 3600.0
