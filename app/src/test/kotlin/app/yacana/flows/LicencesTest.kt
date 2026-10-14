// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertTrue

/** "Settings · about" opens every licence, and each one shows its whole text with its copyright. */
class LicencesTest : FlowTest() {
    @Test
    fun aboutOpensTheLicencesAndEachShowsItsText() {
        show(Section.Night, Screen.Settings, Screen.About)
        assertTrue(appears("not affiliated with ZWO", substring = true))

        tap("Open-source licences")
        tap("Tabler Icons")

        assertTrue(appears("Copyright (c) 2020-2026 Paweł Kuna", substring = true))
        assertTrue(appears("Permission is hereby granted", substring = true))
    }

    @Test
    fun dataShowsWhoseItIsAndItsLicence() {
        show(Section.Night, Screen.Settings, Screen.About, Screen.Licences)

        tap("Gaia (ESA)")

        assertTrue(appears("Gaia Data Processing and Analysis Consortium", substring = true))
        assertTrue(appears("CC BY-NC 3.0 IGO", substring = true))
    }
}
