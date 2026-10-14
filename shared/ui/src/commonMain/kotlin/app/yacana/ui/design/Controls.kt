// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.yacana.ui.YacanaIcons

/**
 * A row of a list of devices or switches, as the design kit's `dv` rows: an icon, a name with a line
 * under it, and something at the end, such as a chevron or a switch. Tappable if [onClick] is set;
 * a line separates it from the next row unless it is the [last].
 */
@Composable
@Suppress("LongParameterList") // Each part of the row is optional.
fun DeviceRow(
    title: String,
    supporting: String?,
    icon: ImageVector? = null,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) = DeviceRow(AnnotatedString(title), supporting, icon, supportingColor, last, onClick, trailing)

/** A [DeviceRow] whose name has parts in other colours, such as a tag after it. */
@Composable
@Suppress("LongParameterList") // Each part of the row is optional.
fun DeviceRow(
    title: AnnotatedString,
    supporting: String?,
    icon: ImageVector? = null,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.Min)
                .then(
                    when {
                        onLongClick != null -> Modifier.combinedClickable(onLongClick = onLongClick, onClick = onClick ?: {})
                        onClick != null -> Modifier.clickable(onClick = onClick)
                        else -> Modifier
                    },
                )
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = supportingColor)
            }
            trailing()
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** A chevron at the end of a [DeviceRow] that opens a screen. */
@Composable
fun RowChevron() {
    Icon(YacanaIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A button on the raised surface, as the designs' grey buttons ("Go to…", "Sync", the
 * focuser's moves): a pill with an optional icon.
 */
@Composable
fun SurfaceButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val content = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Touch.Main),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Readings in two columns, each a muted label before its value, as the design kit's `g2` grids ("RA 00h 42m 44s"). */
@Composable
fun ReadingGrid(readings: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        readings.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (name, value) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = label)) { append(name) }
                            append(" ")
                            append(value)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A card of the design kit (`cd`): the lower surface, rounded, inset from the screen's edges;
 * [highlighted] gives it the accent's outline, and [warning] the error colour's, as a warning's.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    warning: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border =
        when {
            warning -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
            highlighted -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            else -> null
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}
