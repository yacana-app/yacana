// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.flats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.cancel
import app.yacana.ui.resources.flats_countdown_beeps
import app.yacana.ui.resources.flats_countdown_place
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * "Flats · screen": the seconds left to lay the phone face down on the telescope, counted down
 * before [onGo], and what the beeps and a double tap do; "Cancel" goes back.
 */
@Composable
fun FlatsCountdown(onGo: () -> Unit, onCancel: () -> Unit) {
    var left by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1.seconds)
            left--
        }
        onGo()
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(left.toString(), style = MaterialTheme.typography.displayLarge)
            Text(
                stringResource(Res.string.flats_countdown_place),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                stringResource(Res.string.flats_countdown_beeps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        SurfaceButton(stringResource(Res.string.cancel), onCancel, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** The time to lay the phone face down on the telescope when it is the light. */
internal const val COUNTDOWN_SECONDS = 30
