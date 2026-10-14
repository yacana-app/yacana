// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import app.yacana.ui.Section
import app.yacana.ui.sky.SKY_MAP
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Sky · explorer": any area of the sky framed from the map's centre and added to the sequence, as on the first night. */
class FrameHereTest : FlowTest() {
    @Test
    fun theMapsCentreIsFramedAndAddedByItsCoordinates() {
        val services = show(Section.Sky)

        tapIcon("Frame here")
        tap("Add to the sequence")

        // The map opens on M 31: its centre, named to the minute, whatever object is there.
        val names = services.targets.targets.value.map { it.name }
        assertTrue(names.any { Regex("""\d\dh \d\dm [+−]\d\d° \d\d′""").matches(it) }, "the targets: $names")
    }

    @Test
    fun aMosaicKeepsItsPanelsWhenTheMapIsDragged() {
        val services = show(Section.Sky)
        tapIcon("Frame here")
        // The map is dragged once before the mosaic grows, as the user looks around first.
        compose.onNodeWithTag(SKY_MAP).performTouchInput { swipeLeft() }
        settle()
        tapIcon("Columns: more")
        tapIcon("Rows: more")

        // Dragged again, the map moves the mosaic's centre and leaves its columns and rows as they are.
        compose.onNodeWithTag(SKY_MAP).performTouchInput { swipeRight() }
        settle()
        tap("Add to the sequence")

        assertEquals(4, services.targets.targets.value.last().panels.size)
    }
}
