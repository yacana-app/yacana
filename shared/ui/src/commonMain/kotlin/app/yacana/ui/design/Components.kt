// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yacana.ui.YacanaIcons
import kotlinx.coroutines.launch

/** Touch targets (docs/design/STYLE.md): 48 dp at least, 56 dp for the main actions. */
object Touch {
    val Min = 48.dp
    val Main = 56.dp
}

/** A card of a section: a title, an optional line under it, and its content. Tappable if [onClick] is set. */
@Composable
fun YacanaCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors) { body() }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors) { body() }
    }
}

/**
 * What a status means. In red mode green and amber look the same, so each kind also has
 * its own shape and its own word (docs/design/STYLE.md).
 */
enum class StatusKind { Ok, Busy, Warning, Error, Off }

/** A status: a mark whose shape says the kind, then the text that says it in words. */
@Composable
fun StatusLine(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusMark(kind)
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A filled circle for OK, a ring for busy, a square for warning, a diamond for error, a dash for off. */
@Composable
fun StatusMark(kind: StatusKind, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val mark = Modifier.size(10.dp)
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (kind) {
            StatusKind.Ok -> Box(mark.clip(CircleShape).background(colors.tertiary))

            StatusKind.Busy -> Box(
                mark.clip(CircleShape).background(colors.primary).padding(2.dp).clip(CircleShape).background(colors.surface),
            )

            StatusKind.Warning -> Box(mark.background(colors.primary))

            StatusKind.Error -> Box(Modifier.size(9.dp).rotate(DIAMOND).background(colors.error))

            StatusKind.Off -> Box(Modifier.size(width = 10.dp, height = 2.dp).background(colors.onSurfaceVariant))
        }
    }
}

private const val DIAMOND = 45f

/** A label over a value, with fixed-width digits, such as "Alt" over "52° 10′". */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
    }
}

/** A row of a list: at least 48 dp high, with an optional trailing text or control. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().heightIn(min = Touch.Min).then(clickable).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/**
 * A button for actions with risk, such as park or delete: it acts only after it is held
 * for [holdMillis]. A tap does nothing but say so: in [hint] under it, or, where there is no room
 * for one, through [onShortTap]. Accessibility services trigger it with a long click. [danger]
 * outlines it in the error colour, as the designs' delete buttons.
 */
@Composable
@Suppress("LongParameterList") // A risky action's button has several optional parts.
fun HoldButton(
    text: String,
    hint: String?,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMillis: Int = 1_000,
    icon: ImageVector? = null,
    onShortTap: () -> Unit = {},
    danger: Boolean = false,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // The gesture outlives a composition: it calls what the latest one passed.
    val confirm by rememberUpdatedState(onConfirm)
    val shortTap by rememberUpdatedState(onShortTap)
    val colors = MaterialTheme.colorScheme
    val content = when {
        !enabled -> colors.onSurfaceVariant
        danger -> colors.error
        else -> colors.onSurface
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.Main)
                .clip(RoundedCornerShape(28.dp))
                .then(
                    if (danger) {
                        Modifier.border(1.5.dp, content, RoundedCornerShape(28.dp))
                    } else {
                        Modifier.background(colors.surfaceContainerHigh)
                    },
                )
                .semantics {
                    role = Role.Button
                    if (enabled) {
                        onLongClick(label = text) {
                            onConfirm()
                            true
                        }
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        val hold = scope.launch { progress.animateTo(1f, tween(holdMillis, easing = LinearEasing)) }
                        waitForUpOrCancellation()
                        hold.cancel()
                        scope.launch {
                            if (progress.value >= 1f) confirm() else shortTap()
                            progress.snapTo(0f)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // As tall as the button, whatever room there is around it.
            Box(Modifier.matchParentSize()) {
                val fill = if (danger) colors.errorContainer else colors.primaryContainer
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(fill))
            }
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** For previews and the component gallery: a colour swatch with its name. */
@Composable
internal fun Swatch(name: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A number set with buttons instead of the system keyboard, which red mode cannot tint
 * (docs/design/STYLE.md): one button per entry of [steps] each way.
 */
@Composable
fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    steps: List<Int>,
    format: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.sortedDescending().forEach { step ->
            OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−$step") }
        }
        Text(
            format(value),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        steps.sorted().forEach { step ->
            OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+$step") }
        }
    }
}

/**
 * A chip that shows a setting and opens its choices, as the designs' setting chips
 * ("10 s", "Gain 80"): filled, with no outline. [selected] gives it the accent fill, as the
 * designs' chosen chips.
 */
@Composable
fun SettingChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The main action of a screen, as the designs' filled buttons: the accent fill, gold on dark and
 * dark red in red mode, where a bright filled button would light up the night.
 */
@Composable
fun AccentButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors =
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        content = content,
    )
}
