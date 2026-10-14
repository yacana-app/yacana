// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.solving

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.yacana.domain.solver.Arrival
import app.yacana.domain.solver.ArrivalStep
import app.yacana.domain.solver.DatabaseProblem
import app.yacana.domain.solver.StarDatabase
import app.yacana.domain.solver.StarDatabasesState
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatSize
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.solver_download_failed
import app.yacana.ui.resources.solver_downloading
import app.yacana.ui.resources.solver_import_failed
import app.yacana.ui.resources.solver_installing
import app.yacana.ui.resources.solver_no_space
import app.yacana.ui.resources.solver_not_database
import app.yacana.ui.resources.solver_waiting_wifi
import org.jetbrains.compose.resources.stringResource

/** How far a database has come, such as "Downloading · 35% of 1.2 GB". */
@Composable
fun arrivalText(arrival: Arrival): String {
    val percent = if (arrival.totalBytes > 0) (arrival.doneBytes * PERCENT / arrival.totalBytes).toInt() else 0
    val total = formatSize(arrival.totalBytes, decimalComma())
    return when (arrival.step) {
        ArrivalStep.WaitingForWifi -> stringResource(Res.string.solver_waiting_wifi, percent, total)
        ArrivalStep.Downloading -> stringResource(Res.string.solver_downloading, percent, total)
        ArrivalStep.Installing -> stringResource(Res.string.solver_installing, percent)
    }
}

/** The fields a database suits, such as "0.6–6°". */
@Composable
fun fieldsText(database: StarDatabase): String {
    val comma = decimalComma()
    fun degrees(value: Double) = formatDecimal(value, 2, comma).trimEnd('0').trimEnd(',', '.')
    return "${degrees(database.minFieldDeg)}–${degrees(database.maxFieldDeg)}°"
}

/** Why the last download or import did not happen, in the error colour; nothing if it did. */
@Composable
fun DatabaseProblemText(state: StarDatabasesState, modifier: Modifier = Modifier) {
    val comma = decimalComma()
    val text =
        when (val problem = state.problem) {
            is DatabaseProblem.NoSpace -> stringResource(
                Res.string.solver_no_space,
                problem.name,
                formatSize(problem.neededBytes, comma),
                formatSize(problem.freeBytes, comma),
            )

            DatabaseProblem.NotADatabase -> stringResource(Res.string.solver_not_database)

            is DatabaseProblem.ImportFailed -> stringResource(Res.string.solver_import_failed, problem.message)

            null -> state.files.failed?.let { stringResource(Res.string.solver_download_failed, it) }
        }
    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = modifier)
}

private const val PERCENT = 100
