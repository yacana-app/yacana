// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import app.yacana.ui.Section
import app.yacana.ui.theme.YacanaThemeMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Sky · Sky Atlas · searching in red mode": the app's own keyboard, never the system's. */
class RedKeyboardTest : FlowTest() {
    override val mode = YacanaThemeMode.Red

    @Test
    fun theSearchTypesOnTheAppsKeyboard() {
        show(Section.Sky)
        tap("List")

        // No text field the system's keyboard would open for.
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        tap("Search M 31, NGC 7000, Veil…")
        assertTrue(keyboard())

        listOf("m", "3", "3").forEach(::tap)
        assertTrue(appears("Triangulum Galaxy"))
        tapIcon("Delete")
        tap("1")
        assertTrue(appears("Andromeda Galaxy"))

        tapIcon("Hide the keyboard")
        assertFalse(keyboard())
    }

    private fun keyboard() = compose.onAllNodesWithContentDescription("Hide the keyboard").fetchSemanticsNodes().isNotEmpty()
}
