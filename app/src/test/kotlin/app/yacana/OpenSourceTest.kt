// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import app.yacana.ui.settings.OPEN_SOURCE
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Settings · open-source licences" names every library the APK carries, as the build lists them
 * (the task runtimeGroups), and the app has each licence's text.
 */
class OpenSourceTest {
    private val groups = File(System.getProperty("yacana.runtimeGroups")).readLines().filter { it.isNotBlank() }
    private val covered = OPEN_SOURCE.flatMap { it.groups }

    private fun covers(entry: String, group: String) = if (entry.endsWith(".")) group.startsWith(entry) else group == entry

    @Test
    fun everyLibraryOfTheApkIsInTheLicences() {
        assertTrue(groups.isNotEmpty())
        assertEquals(
            emptyList(),
            groups.filterNot { group ->
                covered.any { covers(it, group) }
            },
            "Maven groups with no licence in the app",
        )
    }

    @Test
    fun noLicenceNamesALibraryTheApkNoLongerCarries() {
        assertEquals(emptyList(), covered.filterNot { entry -> groups.any { covers(entry, it) } }, "groups of the licences not in the APK")
    }

    @Test
    fun eachLicenceHasItsTextAndEachIdIsOne() {
        val texts = File("../shared/ui/src/commonMain/composeResources/files/licences")
        OPEN_SOURCE.flatMap { it.texts }.distinct().forEach { assertTrue(File(texts, "$it.txt").isFile, "files/licences/$it.txt") }
        assertEquals(OPEN_SOURCE.size, OPEN_SOURCE.map { it.id }.distinct().size)
    }
}
