// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SkyBrowsingTest {
    private val file = Path(SystemTemporaryDirectory, "yacana-sky-${Random.nextLong().toULong()}.json")

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
    }

    @Test
    fun theMapOpensOnM31AtFirst() {
        assertEquals(MapPlace(0.7123, 41.27, 8.0), SkyBrowsing(file).place.value)
    }

    @Test
    fun theMapOpensWhereItWasLeftAfterTheAppIsClosed() {
        val orion = MapPlace(5.59, -5.39, 3.0)
        SkyBrowsing(file).apply {
            moveTo(orion)
            save()
        }
        assertEquals(orion, SkyBrowsing(file).place.value)
    }

    @Test
    fun aPlaceNotSavedIsOnlyKeptWhileTheAppIsOpen() {
        val browsing = SkyBrowsing(file)
        browsing.moveTo(MapPlace(5.59, -5.39, 3.0))
        assertEquals(MapPlace(5.59, -5.39, 3.0), browsing.place.value)
        assertEquals(MapPlace(), SkyBrowsing(file).place.value)
    }

    @Test
    fun theAtlasFiltersAreKeptAsTheyAreChosen() {
        val chosen = AtlasFilters(groups = setOf(ObjectGroup.Nebulae), maxMagnitude = 10.0, order = AtlasOrder.Brightest)
        SkyBrowsing(file).filter(chosen)

        val again = SkyBrowsing(file)
        assertEquals(chosen, again.filters.value)
        assertEquals(MapPlace(), again.place.value, "the map's place stays as it was")
    }

    @Test
    fun theFiltersStartAsTheAtlasDoes() {
        assertEquals(AtlasFilters(), SkyBrowsing(file).filters.value)
    }

    @Test
    fun aFileThatCannotBeReadOpensOnM31() {
        SystemFileSystem.sink(file).buffered().use { it.writeString("not a place") }
        assertEquals(MapPlace(), SkyBrowsing(file).place.value)
    }
}
