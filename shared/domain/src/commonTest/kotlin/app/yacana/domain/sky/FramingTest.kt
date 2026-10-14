// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FramingTest {
    /** The Seestar S50 Pro's field, 1.35° × 2.40°. */
    private val seestar = 1.35 to 2.40

    private fun item(major: Double?, minor: Double? = major) =
        SkyObject(listOf("X"), ObjectType.Galaxy, SkyPosition(0.0, 0.0), "And", major, minor, null, null, null, emptyList(), null)

    @Test
    fun asTheDesignsSkyAtlas() {
        // M 33 and the Western Veil fit; M 31, the North America Nebula and the Pleiades take a mosaic of 2.
        assertEquals(1, item(70.0, 42.0).panels(seestar))
        assertEquals(1, item(70.0, 6.0).panels(seestar))
        assertEquals(2, item(190.0, 60.0).panels(seestar))
        assertEquals(2, item(120.0, 100.0).panels(seestar))
        assertEquals(2, item(110.0, 110.0).panels(seestar))
    }

    @Test
    fun nothingWithoutTheSizeOrTheField() {
        assertNull(item(null).panels(seestar))
        assertNull(item(70.0).panels(null))
    }

    @Test
    fun favouritesStayOnThePhone() {
        val file = Path(SystemTemporaryDirectory, "yacana-favourites-${kotlin.random.Random.nextLong()}.txt")
        try {
            Favourites(file).toggle("M 31")
            Favourites(file).apply { toggle("M 33") }
            assertEquals(setOf("M 31", "M 33"), Favourites(file).marked.value)
            Favourites(file).toggle("M 31")
            assertEquals(setOf("M 33"), Favourites(file).marked.value)
        } finally {
            if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
        }
    }
}
