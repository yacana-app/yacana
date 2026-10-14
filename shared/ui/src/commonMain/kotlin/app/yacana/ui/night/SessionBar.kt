// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.CaptureActivity
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.overview
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.Touch
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.run_pause
import app.yacana.ui.resources.run_paused
import app.yacana.ui.resources.run_resume
import app.yacana.ui.resources.session_bar_frame
import app.yacana.ui.resources.session_bar_open
import app.yacana.ui.sequence.step
import app.yacana.ui.sequence.stepText
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The session bar, as the design under "Sequence · simple editor": on every screen while a
 * sequence goes on, what it takes and how far the frame is, with the button that pauses it or
 * goes on in one tap. A tap on the bar opens Night, where "Hold to stop" stops it.
 */
@Composable
fun SessionBar(run: SequenceRun, activity: CaptureActivity, clock: Clock, onPause: () -> Unit, onResume: () -> Unit, onOpen: () -> Unit) {
    // The frame's progress ticks every second.
    var now by remember { mutableStateOf(clock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = clock.now()
            delay(1.seconds)
        }
    }
    val overview = run.overview(now, DitherOptions())
    val paused = run.status == RunStatus.Paused
    val exposures = overview.exposures
    val exposing = activity as? CaptureActivity.Exposing
    val open = stringResource(Res.string.session_bar_open)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clickable(onClickLabel = open, onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(YacanaIcons.Camera, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                val doing =
                    if (exposures != null) {
                        listOfNotNull(
                            overview.name,
                            exposures.filter,
                            "${seconds(exposures.exposureSeconds)} s",
                            stringResource(Res.string.session_bar_frame, overview.frame, exposures.count),
                        ).joinToString(" · ")
                    } else {
                        run.step()?.let { stepText(it) }.orEmpty()
                    }
                Text(
                    if (paused) "${stringResource(Res.string.run_paused)} · $doing" else doing,
                    style = MaterialTheme.typography.bodyMedium,
                    // Two lines where the language's words are long, so the frame's count is never lost.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val done = exposing?.let { ((now - it.started).inWholeMilliseconds / MILLIS / it.seconds).toFloat().coerceIn(0f, 1f) } ?: 0f
                LinearProgressIndicator(
                    progress = { if (paused) 0f else done },
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                    drawStopIndicator = {},
                )
            }
            IconButton(onClick = if (paused) onResume else onPause, modifier = Modifier.size(Touch.Min)) {
                Icon(
                    if (paused) YacanaIcons.Play else YacanaIcons.Pause,
                    contentDescription = stringResource(if (paused) Res.string.run_resume else Res.string.run_pause),
                )
            }
        }
    }
}

/** "60" or "0.5": an exposure's seconds. */
private fun seconds(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString()

private const val MILLIS = 1_000.0
