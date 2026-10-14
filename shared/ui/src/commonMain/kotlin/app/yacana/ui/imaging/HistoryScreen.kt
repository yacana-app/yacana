// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.DiscardReason
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.HistoryFrame
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.SectionCard
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.history_af
import app.yacana.ui.resources.history_discarded
import app.yacana.ui.resources.history_empty
import app.yacana.ui.resources.history_legend_hfr
import app.yacana.ui.resources.history_legend_stars
import app.yacana.ui.resources.history_no_stars
import app.yacana.ui.resources.history_none_discarded
import app.yacana.ui.resources.history_quality
import app.yacana.ui.resources.history_reason_bright
import app.yacana.ui.resources.history_reason_clouds
import app.yacana.ui.resources.history_reason_few_stars
import app.yacana.ui.resources.history_tonight
import app.yacana.ui.resources.history_tonight_discarded
import app.yacana.ui.theme.LocalPhotoFilter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * The session's saved frames as "Imaging · history": a chart of each frame's stars and
 * HFR, with the autofocus runs marked, then the frames, newest first, the discarded ones with why.
 * [onlyDiscarded] is the top bar's filter.
 */
@Composable
fun HistoryScreen(capture: CaptureController, autofocus: Autofocus, zone: () -> TimeZone, onlyDiscarded: Boolean) {
    val frames by capture.history.frames.collectAsState()
    val runs by autofocus.runs.collectAsState()
    if (frames.isEmpty()) {
        Text(
            stringResource(Res.string.history_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val shown = remember(frames, onlyDiscarded) { (if (onlyDiscarded) frames.filter { it.discarded != null } else frames).asReversed() }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            SectionCard(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (onlyDiscarded) {
                            stringResource(Res.string.history_tonight_discarded, shown.size, frames.size)
                        } else {
                            stringResource(Res.string.history_tonight, frames.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Legend()
                }
                HistoryChart(
                    frames,
                    runs.map { it.at },
                    stringResource(Res.string.history_af),
                    zone,
                    Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(top = 6.dp),
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(Res.string.history_none_discarded),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        itemsIndexed(shown, key = { _, frame -> frame.number }) { index, frame ->
            FrameRow(frame, zone, first = index == 0, last = index == shown.lastIndex)
        }
    }
}

/** "— HFR" in the line's colour and a square of the bars' for the stars. */
@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.history_legend_hfr),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LEGEND_ALPHA)))
            Text(
                stringResource(Res.string.history_legend_stars),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

/**
 * One frame as the design's list draws it: its thumbnail, "#34 · 22:40 · IR-cut 60 s", and its
 * HFR and stars, or why it is discarded. The rows together look like one card.
 */
@Composable
private fun FrameRow(frame: HistoryFrame, zone: () -> TimeZone, first: Boolean, last: Boolean) {
    val comma = decimalComma()
    val corner = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp,
        topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp,
        bottomEnd = if (last) corner else 0.dp,
    )
    val what = listOfNotNull(
        frame.type.takeIf { it != FrameType.Light }?.let { stringResource(it.title) },
        frame.filter,
        "${formatSeconds(frame.exposureSeconds, comma)} s",
    ).joinToString(" ")
    val quality =
        if (frame.stars == 0) {
            stringResource(Res.string.history_no_stars)
        } else {
            stringResource(Res.string.history_quality, formatDecimal(frame.hfr, 1, comma), frame.stars)
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(start = 16.dp, end = 16.dp, top = if (first) 12.dp else 6.dp, bottom = if (last) 12.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = remember(frame.thumbnail) { frame.thumbnail?.toImageBitmap() }
        val thumbnailShape = RoundedCornerShape(8.dp)
        Box(Modifier.size(THUMBNAIL_SIZE).clip(thumbnailShape).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            bitmap?.let {
                Image(
                    it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    colorFilter = LocalPhotoFilter.current,
                )
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (frame.discarded != null) {
                    Icon(YacanaIcons.CircleX, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                }
                Text("#${frame.number} · ${clockTime(frame.at, zone())} · $what", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                frame.discarded?.let { stringResource(Res.string.history_discarded, quality, stringResource(it.text)) } ?: quality,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal val DiscardReason.text
    get() =
        when (this) {
            DiscardReason.Clouds -> Res.string.history_reason_clouds
            DiscardReason.FewStars -> Res.string.history_reason_few_stars
            DiscardReason.BrightBackground -> Res.string.history_reason_bright
        }

/** "22:40" in [zone]. */
internal fun clockTime(at: Instant, zone: TimeZone): String {
    val time = at.toLocalDateTime(zone).time
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

private val CHART_HEIGHT = 112.dp
private val THUMBNAIL_SIZE = 40.dp
private const val LEGEND_ALPHA = 0.5f
