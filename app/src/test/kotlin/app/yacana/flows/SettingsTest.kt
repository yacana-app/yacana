// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.ProfileOptions
import app.yacana.ui.AppServices
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The profile's settings as they are touched: plate solving, its star databases, autofocus and each filter's focus offset. */
class SettingsTest : FlowTest() {
    /** The options of the profile in use. */
    private fun AppServices.options(): ProfileOptions = checkNotNull(equipment.state.value.active).options

    @Test
    fun theLogIsExportedFromSettings() {
        show(Section.Night, Screen.Settings)

        tap("Export the log")

        assertEquals(1, logExports)
    }

    @Test
    fun theDownsamplingIsPickedOnItsSheet() {
        val services = show(Section.Equipment, Screen.PlateSolving)

        tap("Downsampling")
        tap("2 × 2")

        assertEquals(2, services.options().solver.downsample)
        assertTrue(appears("2 × 2"))
    }

    @Test
    fun theSearchRadiusIsTypedOnTheKeypad() {
        val services = show(Section.Equipment, Screen.PlateSolving)

        tap("Search radius")
        listOf("1", "5").forEach { tap(it) }
        tap("Save")

        assertEquals(15.0, services.options().solver.searchRadiusDeg)
    }

    @Test
    fun aStarDatabaseIsDownloadedAndItsDownloadCancelled() {
        show(Section.Equipment, Screen.PlateSolving)

        // The last database the phone does not have is W08's; D80's is on its way.
        tap("Download")
        tap("Cancel")

        assertEquals(listOf("download W08", "cancel D80"), databaseRequests())
    }

    @Test
    fun theAutofocusStepIsTypedOnTheKeypad() {
        val services = show(Section.Equipment, Screen.AutofocusSettings)

        tap("Step")
        listOf("4", "0").forEach { tap(it) }
        tap("Save")

        assertEquals(40, services.options().autofocus.stepSize)
    }

    @Test
    fun theAutofocusPointsArePicked() {
        val services = show(Section.Equipment, Screen.AutofocusSettings)

        tap("Points")
        tap("11")

        assertEquals(5, services.options().autofocus.offsetSteps)
    }

    @Test
    fun theAutofocusFittingIsPicked() {
        val services = show(Section.Equipment, Screen.AutofocusSettings)

        tap("Fitting")
        tap("Parabolic")

        assertEquals(FocusFitting.Parabolic, services.options().autofocus.fitting)
    }

    @Test
    fun aFiltersFocusOffsetIsTypedWithItsSign() {
        val services = show(Section.Equipment, Screen.Device(DeviceRole.Focuser), connected = true)
        assertTrue(appears("Focus offset by filter"))

        tap("LP")
        tap("−")
        listOf("2", "5").forEach { tap(it) }
        tap("Save")

        assertEquals(mapOf("LP" to -25), services.options().autofocus.filterOffsets)
        assertTrue(appears("−25"))
    }
}
