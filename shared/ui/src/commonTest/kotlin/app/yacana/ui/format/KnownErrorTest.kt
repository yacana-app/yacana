// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.format

import app.yacana.ui.resources.Res
import app.yacana.ui.resources.error_no_camera
import app.yacana.ui.resources.error_no_filter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnownErrorTest {
    @Test
    fun theAppsOwnErrorsHaveTheirTextAndWhatTheyName() {
        assertEquals(KnownError(Res.string.error_no_filter, listOf("Ha")), knownError("The filter wheel has no Ha"))
        assertEquals(KnownError(Res.string.error_no_camera, emptyList()), knownError("The profile has no camera connected"))
    }

    @Test
    fun anyOtherShowsAsItCame() {
        assertNull(knownError("Failed to connect to /192.168.0.101:32323"))
        assertNull(knownError("Device error 0x401: the wheel has no Ha"))
    }
}
