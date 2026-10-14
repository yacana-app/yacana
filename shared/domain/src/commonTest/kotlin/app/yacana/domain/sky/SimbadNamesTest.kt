// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.sky.SimbadNames.Queries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Names as SIMBAD writes them, to search those like a name it does not know as typed. */
class SimbadNamesTest {
    @Test
    fun aConstellationInLatinOrSpanishIsItsStars() {
        assertEquals(Queries("% Ret", "*reticuli*"), SimbadNames.queries("reticuli"))
        assertEquals(Queries("% Ret", "*reticulo*"), SimbadNames.queries("Retículo"))
        assertEquals(Queries("% Ret", "*reticulum*"), SimbadNames.queries("reticulum"))
    }

    @Test
    fun aGreekLetterNamesItsStars() {
        assertEquals(Queries("* zet% Ret", "*zeta*reticuli*"), SimbadNames.queries("Zeta Reticuli"))
        assertEquals(Queries("* alf% Cen", "*alfa*centauro*"), SimbadNames.queries("alfa centauro"))
        assertEquals(Queries("* zet% Ret", "*zeta01*reticuli*"), SimbadNames.queries("zeta01 reticuli"))
    }

    @Test
    fun aNameOfSeveralWordsIsOneConstellation() {
        assertEquals(Queries("* alf% UMa", "*alpha*ursae*majoris*"), SimbadNames.queries("alpha ursae majoris"))
        assertEquals(Queries("* alf% Cru", "*alfa*cruz*del*sur*"), SimbadNames.queries("alfa cruz del sur"))
        assertEquals(Queries("% Ori", "*orion*"), SimbadNames.queries("Orión"))
    }

    @Test
    fun otherNamesAreOnlySearchedAsTheyAreTyped() {
        assertEquals(Queries(null, "*ton*6*"), SimbadNames.queries("TON 6"))
        assertEquals(Queries(null, "*ji*"), SimbadNames.queries("ji"))
        assertNull(SimbadNames.queries("  "))
    }
}
