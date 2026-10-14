// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.CapturedFrame
import app.yacana.domain.sky.FrameSky
import app.yacana.domain.sky.MapLayer
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.solver.PlateSolving
import app.yacana.domain.solver.SolveState
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.reference_back
import app.yacana.ui.resources.reference_solving
import app.yacana.ui.resources.reference_survey
import app.yacana.ui.resources.sky_no_cache
import app.yacana.ui.resources.sky_survey
import app.yacana.ui.sky.MapView
import app.yacana.ui.sky.SkyPhoto
import app.yacana.ui.sky.drawnLayers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Imaging's "Reference": the photo of the caches of the field of the frame on screen, to
 * compare them. It is the frame's, so it needs the frame solved, and a new frame hides it.
 */
@Stable
internal class FrameReference(private val solving: PlateSolving, private val scope: CoroutineScope) {
    /** The frame whose reference is on screen, and its sky; null when the frame is shown. */
    var shown by mutableStateOf<Pair<CapturedFrame, FrameSky>?>(null)
        private set

    /** Solving the frame for its reference. */
    var solvingFrame by mutableStateOf(false)
        private set

    private var kept: Pair<CapturedFrame, FrameSky>? = null

    /**
     * Shows the reference of [frame], solving it first if it was not, or goes back to the frame;
     * [onFailed] when there is no frame or it does not solve, for the solve's sheet to say why.
     */
    fun toggle(frame: CapturedFrame?, onFailed: () -> Unit) {
        when {
            shown != null -> shown = null

            kept?.first === frame -> shown = kept

            frame == null -> {
                solving.solveLastFrame()
                onFailed()
            }

            !solvingFrame -> scope.launch {
                solvingFrame = true
                val solved = solving.solve(frame) as? SolveState.Solved
                solvingFrame = false
                val sky = solved?.let { FrameSky.of(it.solution, it.frameWidth, it.frameHeight) }
                if (sky == null) {
                    onFailed()
                } else {
                    kept = frame to sky
                    shown = kept
                }
            }
        }
    }

    /** A new frame on screen hides the reference of the one before. */
    fun showing(frame: CapturedFrame?) {
        if (shown?.first !== frame) shown = null
    }
}

@Composable
internal fun rememberReference(solving: PlateSolving): FrameReference {
    val scope = rememberCoroutineScope()
    return remember(solving) { FrameReference(solving, scope) }
}

/**
 * The caches' photo of the frame's field over the frame, as the design "Imaging · reference":
 * turned, scaled and zoomed as the frame on screen at [viewport], flipped when the frame is
 * mirrored, and only where the frame is, so it shows the same field and no more.
 */
@Composable
internal fun ReferencePhoto(sky: FrameSky, viewport: Viewport, caches: SkyCacheStore?, modifier: Modifier) {
    val inUse by (caches?.layers ?: remember { MutableStateFlow(emptyList<MapLayer>()) }).collectAsState()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val view = sky.view(viewport.centerX, viewport.centerY, viewport.scale, width.toDouble(), height.toDouble())
        val map = MapView(view.center, view.fieldDeg, view.rollDeg)
        val layers = drawnLayers(inUse, map, IntSize(width, height))
        val left = (-viewport.centerX * viewport.scale + width / 2.0).toFloat()
        val top = (-viewport.centerY * viewport.scale + height / 2.0).toFloat()
        val right = left + (sky.width * viewport.scale).toFloat()
        val bottom = top + (sky.height * viewport.scale).toFloat()
        SkyPhoto(
            map,
            layers,
            null,
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(left, top, right, bottom) { this@drawWithContent.drawContent() } }
                .graphicsLayer { if (sky.mirrored) scaleX = -1f },
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { InfoChip(stringResource(Res.string.reference_back)) }
    }
}

/** What the photo over the frame is, as the design's chip; without a cache, where to install one. */
@Composable
internal fun ReferenceLabel(caches: SkyCacheStore?, open: (Screen) -> Unit) {
    val inUse by (caches?.layers ?: remember { MutableStateFlow(emptyList<MapLayer>()) }).collectAsState()
    if (inUse.isEmpty()) {
        Box(Modifier.clickable { open(Screen.SkyMapSettings) }) { InfoChip(stringResource(Res.string.sky_no_cache)) }
    } else {
        val survey = stringResource(Res.string.sky_survey, inUse.map { it.cache.survey }.distinct().joinToString(" + "))
        InfoChip(stringResource(Res.string.reference_survey, survey))
    }
}

/** While the frame is solved for its reference. */
@Composable
internal fun ReferenceSolving() = InfoChip(stringResource(Res.string.reference_solving))
