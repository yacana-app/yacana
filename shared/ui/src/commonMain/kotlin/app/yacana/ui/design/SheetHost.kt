// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.yacana.ui.YacanaIcons

/** Where sheets and the app's keyboard are drawn: over the whole app, bottom bar included, as in the designs. */
class SheetHost {
    internal var sheet by mutableStateOf<(@Composable () -> Unit)?>(null)
    internal var keyboard by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** Draws the app's keyboard and the open sheet over it, if any; put it last in a box that fills the app. */
    @Composable
    fun Show() {
        keyboard?.invoke()
        sheet?.invoke()
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHost?> { null }

/**
 * A sheet over the bottom of the app while this is in the composition, as in the designs; a tap
 * outside, back or a swipe down closes it. It is drawn by the app's [SheetHost], in the app's own
 * window, unlike Material's bottom sheet: a separate window shows the system bars, which red mode
 * keeps hidden. Without a host, as in the component gallery, it draws where it is. With a
 * [header], the header stays while the rest scrolls, the sheet takes at most three quarters of the
 * screen, and in landscape it opens at the side, with what is under it beside it.
 */
@Composable
fun BottomSheet(onDismiss: () -> Unit, header: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalSheetHost.current
    val dismiss by rememberUpdatedState(onDismiss)
    val top by rememberUpdatedState(header)
    val body by rememberUpdatedState(content)
    if (host == null) {
        SheetLayout(dismiss, top, body)
        return
    }
    DisposableEffect(host) {
        val sheet: @Composable () -> Unit = { SheetLayout(dismiss, top, body) }
        host.sheet = sheet
        onDispose { if (host.sheet === sheet) host.sheet = null }
    }
}

@Composable
private fun SheetLayout(onDismiss: () -> Unit, header: (@Composable RowScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    // Back closes the sheet, not the screen under it: the sheet's handler is the last one added.
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = header != null && maxWidth > maxHeight
        val swipe = rememberSwipeDown(onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
        Surface(
            if (side) {
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(minOf(maxWidth * SIDE_SHARE, SIDE_WIDTH))
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (header != null) Modifier.heightIn(max = maxHeight * HEADED_SHARE) else Modifier)
                    .graphicsLayer { translationY = swipe.offset }
                    .nestedScroll(swipe)
            },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = if (side) 0.dp else 28.dp, bottomStart = if (side) 28.dp else 0.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            if (header == null) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Handle(Modifier.align(Alignment.CenterHorizontally))
                    content()
                }
            } else {
                Headed(header, side, swipe, content)
            }
        }
    }
}

/** A sheet's [header], which stays, and its [content], which scrolls; at the [side], without a handle. */
@Composable
private fun Headed(header: @Composable RowScope.() -> Unit, side: Boolean, swipe: SwipeDown, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.navigationBarsPadding().then(if (side) Modifier.statusBarsPadding() else Modifier)) {
        // The handle and the header drag the sheet down; the rest scrolls first.
        val drag = if (side) Modifier else Modifier.draggable(swipe.state, Orientation.Vertical, onDragStopped = { swipe.release() })
        Column(Modifier.fillMaxWidth().then(drag)) {
            if (!side) Handle(Modifier.align(Alignment.CenterHorizontally))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = if (side) 16.dp else 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = header,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** The designs' handle at the top of a sheet. */
@Composable
private fun Handle(modifier: Modifier) {
    Box(
        modifier
            .padding(top = 12.dp)
            .size(width = 32.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)),
    )
}

/**
 * A sheet dragged down: by its handle and header, or by its content once that is at its top.
 * Let go past [dismissAt] pixels, it closes; before that, it goes back up.
 */
private class SwipeDown(private val dismissAt: Float, private val onDismiss: () -> Unit) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set

    val state = DraggableState { delta -> offset = (offset + delta).coerceAtLeast(0f) }

    fun release() {
        if (offset > dismissAt) onDismiss() else offset = 0f
    }

    // Up, the sheet goes back before its content scrolls.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f || offset == 0f) return Offset.Zero
        val used = maxOf(available.y, -offset)
        offset += used
        return Offset(0f, used)
    }

    // Down, what the content could not scroll moves the sheet.
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
        offset += available.y
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset == 0f) return Velocity.Zero
        release()
        return available
    }
}

@Composable
private fun rememberSwipeDown(onDismiss: () -> Unit): SwipeDown {
    val dismiss by rememberUpdatedState(onDismiss)
    val at = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
    return remember(at) { SwipeDown(at) { dismiss() } }
}

/** How far a sheet is dragged down to close. */
private val DISMISS_DRAG = 96.dp

/** A sheet with a header takes at most this share of the screen's height, so what it filters shows above it. */
private const val HEADED_SHARE = 0.75f

/** In landscape, a sheet with a header opens at the side: half the screen, at most this wide. */
private const val SIDE_SHARE = 0.5f
private val SIDE_WIDTH = 420.dp

private const val SCRIM_ALPHA = 0.6f
